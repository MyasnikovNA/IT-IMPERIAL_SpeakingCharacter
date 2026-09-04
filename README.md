# Speaking Character — MVP backend

Существующее FastAPI-приложение продолжает отдавать D-ID frontend на порту `8000`. Новый Kotlin/Ktor-сервис на порту `8080` отвечает только за запрос к LLM и память диалога в PostgreSQL. D-ID полностью остаётся в браузере: он озвучивает `assistantMessage`, полученное из Kotlin API.

## Локальный запуск

Скопируйте шаблон конфигурации и укажите ключ Gemini. Заполненный файл игнорируется Git.
Для Kotlin-сервиса нужна JDK 17 или новее.

```bash
cp .env.example .env
# Укажите GEMINI_API_KEY в .env
set -a; source .env; set +a
docker compose up -d --wait postgres
cd backend && ./gradlew run
```

В старых версиях Docker Compose без `--wait` используйте `docker compose up -d postgres` и дождитесь состояния `healthy` вручную.

В другом терминале с теми же экспортированными переменными окружения запустите существующий frontend:

```bash
uvicorn app:app --reload --port 8000
```

Откройте `http://localhost:8000`. Frontend получает `CHAT_API_URL` из `GET /api/config`; по умолчанию используется `http://localhost:8080`.

Схема БД применяется Flyway автоматически. При первом запуске Gradle Wrapper скачает Gradle 8.11.1. Для smoke-проверки используйте `curl http://localhost:8080/health`.

## Architecture

Browser frontend отправляет запрос в Kotlin `POST /api/chat`.
Kotlin backend сохраняет и загружает историю из PostgreSQL, затем передаёт ограниченный контекст Gemini.
Ответ Gemini возвращается как `assistantMessage`.
Frontend передаёт только этот ответ в `D-ID agentManager.speak()`.
FastAPI продолжает только отдавать frontend и его конфигурацию.
Kotlin отвечает за LLM и память диалога, а D-ID — за голос и аватар.

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

### История сессии

```bash
curl http://localhost:8080/api/chat/<SESSION_ID>/history
```

## Not implemented yet

Сознательно отложены после MVP:

- streaming LLM response и SSE/WebSocket;
- interruption / barge-in;
- STT / VAD;
- advanced scenario engine;
- context summarization;
- evaluation/report.
