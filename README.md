# Speaking Character — integrated backend

Единый Kotlin/Ktor backend, собранный из трёх backend-направлений проекта:

- **backend** — WebSocket training session, `generationId`, barge-in/cancellation, Gemini streaming, отчёт и метрики;
- **llm-service** — совместимый `/api/chat`, PostgreSQL/Flyway, ограничение LLM-контекста;
- **simli-rnd** — `AVATAR_PROVIDER=did|simli`, Simli session token, Gemini → ElevenLabs PCM16 → Simli WebSocket pipeline и browser latency telemetry.

Frontend-контракты из `llm-service`/`simli-rnd` сохранены, поэтому frontend не должен знать о внутреннем `TrainingSessionManager`.

## Что получилось

### Один источник истины для диалога

`TrainingSessionManager` теперь обслуживает все входы:

- `POST /api/chat` — синхронный контракт llm-service/D-ID frontend;
- `WS /api/chat/stream` — потоковый Simli контракт;
- `WS /ws/training/{sessionId}` — расширенный backend protocol с явным `generationId`;
- `/api/sessions/*` — исходный training API.

История, `latestGenerationId`, partial/interrupted assistant messages, метрики и итоговый report сохраняются одной моделью сессии.

### Barge-in / race safety

- для каждой сессии существует один активный generation job;
- новая генерация сначала повышает `generationId`, затем отменяет старую через `cancelAndJoin()`;
- поздние токены старого generation отбрасываются;
- `interrupt(N)` не может отменить уже начавшийся `N+1`;
- при отмене частично сгенерированный ответ сохраняется как `interrupted=true`.

### LLM

- Gemini streaming используется и для обычного training WS, и для Simli pipeline;
- в prompt передаются только последние `MAX_CONTEXT_MESSAGES` сообщений;
- fallback-модели разрешены только **до первой выданной дельты**, чтобы при ошибке не повторить уже произнесённый текст.

### Storage

Поддержаны два режима:

- `STORAGE_BACKEND=file` — исходное JSON-хранилище;
- `STORAGE_BACKEND=postgres` — PostgreSQL + HikariCP + Flyway.

PostgreSQL хранит полный session aggregate в `integrated_training_sessions.payload JSONB`. Это делает обновление истории + generation/report полей атомарным и не конфликтует со старыми `chat_sessions/chat_messages/training_reports` из веток llm-service/simli-rnd.

> Старые строки из `chat_sessions/chat_messages` автоматически не импортируются. Таблицы не удаляются и не меняются. Если на окружении уже есть ценные исторические данные из старой схемы, их нужно мигрировать отдельным data-migration перед переключением production traffic.

## API

### Health

```http
GET /health
```

Пример:

```json
{
  "status": "ok",
  "storage": "postgres",
  "avatarProvider": "simli"
}
```

### Frontend config

```http
GET /api/config
```

D-ID:

```json
{
  "avatar_provider": "did",
  "chat_api_url": "http://localhost:8080",
  "agent_id": "...",
  "client_key": "..."
}
```

Simli:

```json
{
  "avatar_provider": "simli",
  "chat_api_url": "http://localhost:8080"
}
```

Simli/ElevenLabs API keys и Face ID в браузер не выдаются.

### llm-service compatibility API

```http
POST /api/chat
Content-Type: application/json

{
  "sessionId": null,
  "message": "Здравствуйте",
  "scenario": { "presetId": "sales-discovery" }
}
```

Ответ:

```json
{
  "sessionId": "uuid",
  "assistantMessage": "...",
  "generationId": 0
}
```

Также:

- `GET /api/scenarios` возвращает пять безопасных карточек встроенных тренировок;
- `POST /api/scenarios/validate` принимает `{ "markdown": "..." }` и проверяет одноразовый сценарий до старта;
- `GET /api/chat/{sessionId}/history`
- `POST /api/chat/{sessionId}/finish`
- `GET /api/chat/{sessionId}/report`

`finish` идемпотентен: повторный вызов возвращает сохранённый report без нового Gemini-запроса. Отчёт содержит `overallScore` и оценки критериев в диапазоне 1–5, summary, 1–3 рекомендации, comment и evidence по каждому критерию. Для сценарной тренировки используются критерии закреплённого snapshot; для свободного диалога — три базовых критерия. Некорректный JSON или ошибка Gemini возвращают `502` и не завершают сессию.

Сценарий необязателен и допускается только при создании сессии. Можно передать ровно одно поле: `presetId` или Markdown в `scenario.markdown`. Backend валидирует Markdown до сохранения реплики и закрепляет нормализованный snapshot в сессии: последующие HTTP/WS turn не могут изменить сценарий.

### Simli streaming

1. Получить короткоживущий token:

```http
POST /api/avatar/simli/session
```

2. Открыть:

```text
ws://localhost:8080/api/chat/stream
```

3. Начать turn:

```json
{
  "type": "start",
  "sessionId": null,
  "message": "Здравствуйте",
  "turnId": "optional-uuid"
}
```

Backend выдаёт:

```text
session
text delta ...
audio_frame metadata
<binary PCM16>
...
metrics
done
```

Отмена:

```json
{ "type": "cancel" }
```

Backend отменяет активный generation/TTS pipeline; frontend одновременно должен вызвать `SimliClient.ClearBuffer()`.

### Extended training WebSocket

```text
/ws/training/{sessionId}
```

Сохраняется исходный protocol:

```json
{"type":"user_message","generationId":1,"text":"Здравствуйте"}
```

```json
{"type":"interrupt","generationId":1}
```

Server events: `connected`, `generation_started`, `assistant_delta`, `assistant_segment`, `assistant_completed`, `generation_cancelled`, `stale_generation`, `report_ready`, `error`.

## Конфигурация

Скопировать:

```bash
cp .env.example .env
```

Минимум для D-ID:

```dotenv
GEMINI_API_KEY=...
AVATAR_PROVIDER=did
DID_AGENT_ID=...
DID_CLIENT_KEY=...
```

Для Simli:

```dotenv
GEMINI_API_KEY=...
AVATAR_PROVIDER=simli
SIMLI_API_KEY=...
SIMLI_FACE_ID=...
ELEVENLABS_API_KEY=...
ELEVENLABS_VOICE_ID=...
```

Backend понимает и старые названия из соседних веток:

- `CHAT_API_URL` используется как fallback для `PUBLIC_API_URL`;
- `FRONTEND_HOST` используется как fallback для `ALLOWED_ORIGINS`;
- наличие `DATABASE_URL` автоматически выбирает PostgreSQL, если `STORAGE_BACKEND` явно не указан.

## Запуск

### Docker Compose

```bash
cp .env.example .env
# заполнить ключи

docker compose up --build
```

Compose поднимает PostgreSQL и backend на `localhost:8080`.

### Локально

Нужна JDK 17:

```bash
./gradlew test
./gradlew run
```

Smoke:

```bash
curl http://localhost:8080/health
```

## Frontend

- **D-ID frontend** может использовать `POST /api/chat` и озвучивать `assistantMessage`, как в llm-service.
- **simli-rnd frontend** совместим с `/api/avatar/simli/session`, `/api/chat/stream` и `/api/metrics`.
- Для более продвинутого D-ID barge-in можно использовать `frontend-integration.js` и `/ws/training/{sessionId}`.

То есть выбирать нужно один avatar path на клиенте; backend при этом использует одну session/history модель.

## Проверка этого merge

В текущей изолированной среде выполнены:

- проверка структуры всех четырёх переданных архивов;
- `node --check frontend-integration.js`;
- локальная компиляция и runtime-check независимого `AppConfig.kt` через установленный `kotlinc`;
- синтаксический проход по Kotlin source (без найденных parser-level ошибок);
- добавлены unit-тесты для env compatibility и TTS text/alignment helpers.

Полный `./gradlew test` здесь **не завершён**, потому что Gradle Wrapper не может скачать дистрибутив/зависимости: runtime окружение не имеет DNS/сетевого доступа к `services.gradle.org`/Maven. Это ограничение среды проверки, а не успешный build. Перед merge в основную ветку обязательно запустить в вашей CI/локальной среде:

```bash
./gradlew clean test
```

и затем хотя бы один vendor smoke для выбранного avatar provider.
