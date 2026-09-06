"""Отдаёт frontend и его безопасную публичную runtime-конфигурацию."""

import os
from pathlib import Path

from fastapi import FastAPI
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from dotenv import load_dotenv

BASE_DIR = Path(__file__).resolve().parent
STATIC_DIR = BASE_DIR / "static"

load_dotenv()

app = FastAPI()


@app.middleware("http")
async def disable_html_cache(request, call_next):
    """Не даёт браузеру сочетать актуальные страницы со старым frontend-кешем."""

    response = await call_next(request)
    content_type = response.headers.get("content-type", "")
    if content_type.startswith("text/html"):
        response.headers["cache-control"] = "no-store, max-age=0"
    return response


@app.get("/api/config")
def config():
    """Возвращает публичную конфигурацию frontend без серверных секретов."""

    avatar_provider = os.getenv("AVATAR_PROVIDER", "did").lower()
    response = {
        "avatar_provider": avatar_provider,
        "chat_api_url": os.getenv("CHAT_API_URL", "http://localhost:8080"),
    }

    if avatar_provider == "did":
        response["agent_id"] = (BASE_DIR / "agent_id.txt").read_text(encoding="utf-8").strip()
        response["client_key"] = (BASE_DIR / "client_key.txt").read_text(encoding="utf-8").strip()

    return response


app.mount(
    "/",
    StaticFiles(
        directory=STATIC_DIR,
        html=True
    ),
    name="static"
)
