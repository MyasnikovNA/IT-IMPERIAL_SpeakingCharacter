# Speaking Character — MVP backend

FastAPI-приложение на порту `8000` отдаёт frontend, а Kotlin/Ktor-сервис на `8080` отвечает за LLM и постоянную память диалога в PostgreSQL. Провайдер аватара выбирается `AVATAR_PROVIDER`: `did` сохраняет синхронный D-ID путь, а `simli` включает Gemini SSE → ElevenLabs WebSocket → PCM16 → Simli WebRTC.

В Simli-режиме браузер открывает только исходящие WebSocket/WebRTC-соединения: открытый IP, туннель и публичный callback endpoint не нужны. Ключи Gemini, ElevenLabs и Simli не передаются frontend.

## Локальный запуск

Скопируйте шаблон конфигурации, укажите ключ Gemini и выберите провайдера. Заполненный файл игнорируется Git.
Для Kotlin-сервиса нужна JDK 17 или новее.

```bash
cp .env.example .env
# Укажите GEMINI_API_KEY и AVATAR_PROVIDER в .env
set -a; source .env; set +a
docker compose up -d --wait postgres
cd backend && ./gradlew run
```

В старых версиях Docker Compose без `--wait` используйте `docker compose up -d postgres` и дождитесь состояния `healthy` вручную.

В другом терминале с теми же экспортированными переменными окружения запустите существующий frontend:

```bash
uvicorn app:app --reload --port 8000
```

Откройте `http://localhost:8000`. Frontend получает безопасный `CHAT_API_URL`, `AVATAR_PROVIDER` и, только для D-ID режима, его публичные данные из `GET /api/config`.

Схема БД применяется Flyway автоматически. При первом запуске Gradle Wrapper скачает Gradle 8.11.1. Для smoke-проверки используйте `curl http://localhost:8080/health`.

## Architecture

Обычный D-ID путь остаётся совместимым с MVP. При `AVATAR_PROVIDER=simli` используется отдельный WebSocket API, поэтому один frontend не дублирует озвучивание и не расходует кредиты двух провайдеров.

```text
browser ── WS start ──> Kotlin ── Gemini SSE ──> text delta
   │                       │                       │
   │ <── JSON delta ───────┘                       │
   │                                               v
   │ <── PCM16 binary ── ElevenLabs WebSocket <────┘
   │
   └── Simli SDK sendAudioData(PCM16) ── WebRTC/livekit ──> avatar
```

Перед подключением frontend вызывает `POST /api/avatar/simli/session`. Backend запрашивает у Simli короткоживущий token с лимитами `SIMLI_MAX_SESSION_SECONDS` и `SIMLI_MAX_IDLE_SECONDS`, а браузеру отдаёт только `{ token, transport }`.

## LLM architecture

`ConversationService` координирует обычный и потоковый ход тренировки.
`ConversationContextBuilder` формирует context, а `PromptProvider` предоставляет prompts.
`GeminiClient` выполняет обычный inference и Gemini SSE. Резервная модель используется только до первой streaming-дельты; после первой дельты ошибка завершает поток без повтора уже озвученного текста.
`ElevenLabsStreamingTtsClient` один раз подключается к ElevenLabs на ход, отправляет фрагменты только по границе слова и возвращает `pcm_16000`.
Assistant message сохраняется в PostgreSQL только после штатного окончания Gemini stream. User message сохраняется раньше, в том числе при ошибке или отмене.
После finish `EvaluationService` передаёт полный transcript Gemini и сохраняет structured report.

## Conversation memory

В PostgreSQL сохраняется вся история сессии.
В обычный LLM turn передаются только последние `MAX_CONTEXT_MESSAGES` реплик.
Итоговая evaluation использует полный transcript; для длинных диалогов summarization пока не реализована.

## API

- `GET /health` → `{ "status": "ok" }`
- `POST /api/chat` с `{ "sessionId": "UUID or null", "message": "..." }`
- `GET /api/chat/{sessionId}/history` — хронологическая отладочная история

`MAX_CONTEXT_MESSAGES` ограничивает число последних сообщений `USER`/`ASSISTANT`, передаваемых Gemini. Реплика пользователя сохраняется до построения этого окна, поэтому в запрос к LLM она попадает ровно один раз.

### Пример запроса чата

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"sessionId":null,"message":"Меня зовут Тарас"}'
```

```json
{
  "sessionId": "...",
  "assistantMessage": "..."
}
```

### Потоковый Simli WebSocket

После подключения аватара frontend открывает `ws://localhost:8080/api/chat/stream` и отправляет:

```json
{ "type": "start", "sessionId": null, "message": "Расскажите о следующем шаге" }
```

Сервер возвращает text frames `session`, `delta`, затем бинарные PCM16 frames и финальный `done`. Команда `{ "type": "cancel" }` отменяет Gemini и ElevenLabs coroutine; frontend одновременно вызывает `SimliClient.ClearBuffer()` и закрывает соединение.

## Simli + ElevenLabs configuration

Для потокового режима укажите в локальном `.env`:

```bash
AVATAR_PROVIDER=simli
SIMLI_API_KEY=...
SIMLI_FACE_ID=...
SIMLI_TRANSPORT=livekit
SIMLI_MAX_SESSION_SECONDS=600
SIMLI_MAX_IDLE_SECONDS=60
ELEVENLABS_API_KEY=...
ELEVENLABS_VOICE_ID=...
ELEVENLABS_MODEL=eleven_flash_v2_5
STREAM_MIN_CHARS=50
```

`livekit` выбран как устойчивый Simli transport без собственной ICE-конфигурации в приложении. Модель `eleven_flash_v2_5` запрашивается с `output_format=pcm_16000`, который напрямую принимает Simli SDK.

Цель warm-connection — первое аудио до 1.5 секунды после отправки текста. Для реального smoke нужны ключи и Docker daemon: подключите аватар, произнесите одну короткую реплику и сопоставьте `gemini_first_delta`, `tts_first_audio`, `browser_first_pcm` и `simli_speaking`. Без ключей реальные vendor smoke намеренно не выполняются.

## Метрики и стоимость

Логи не содержат текста реплик, токенов или API keys. Backend фиксирует `gemini_first_delta`, `tts_first_audio`, `tts_stream_completed` (символы и байты PCM) и `stream_completed`. Frontend фиксирует `browser_first_pcm`, `simli_speaking`, `simli_silent` и длительности session/speaking.

Фактические символы, переданные ElevenLabs, логируются как `characters`; для сверки стоимости Flash использует 0.5 credits на символ, Multilingual v2 — 1 credit на символ согласно [правилам ElevenLabs](https://help.elevenlabs.io/hc/en-us/articles/27562020846481-What-are-credits). Simli usage следует сверять по длительности подключённой session и speaking с dashboard: сервис публично указывает 50 бесплатных минут в месяц и pay-as-you-go, но не фиксирует единую публичную ставку за минуту на [странице pricing](https://www.simli.com/). Кнопка «Отключить» закрывает avatar session, чтобы минуты не расходовались в простое.

### История сессии

```bash
curl http://localhost:8080/api/chat/<SESSION_ID>/history
```

## Finish training

```bash
curl -X POST http://localhost:8080/api/chat/<SESSION_ID>/finish
```

Повторный finish возвращает уже сохранённый report и не запускает Gemini повторно.

## Get report

```bash
curl http://localhost:8080/api/chat/<SESSION_ID>/report
```

## Report structure

```json
{
  "sessionId": "...",
  "overallScore": 4,
  "summary": "Тренировка в целом пройдена успешно.",
  "recommendations": ["Чётче проговаривать следующий шаг"],
  "criteria": [
    {
      "name": "Полнота ответа",
      "score": 4,
      "comment": "Основные элементы ответа присутствуют.",
      "evidence": "Пользователь обозначил следующий шаг."
    }
  ]
}
```

## Not implemented yet

Сознательно отложены после MVP:

- interruption / barge-in;
- STT / VAD;
- generationId;
- advanced scenario engine;
- context summarization;
- RAG;
- production authentication.
