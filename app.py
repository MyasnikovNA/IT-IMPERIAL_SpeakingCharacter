"""Отдаёт существующий D-ID frontend и его runtime-конфигурацию."""

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


@app.get("/api/config")
def config():
    """Возвращает публичную конфигурацию frontend без серверных секретов."""

    agent_id = (
        BASE_DIR / "agent_id.txt"
    ).read_text(
        encoding="utf-8"
    ).strip()

    client_key = (
        BASE_DIR / "client_key.txt"
    ).read_text(
        encoding="utf-8"
    ).strip()

    return {
        "agent_id": agent_id,
        "client_key": client_key,
        "chat_api_url": os.getenv("CHAT_API_URL", "http://localhost:8080"),
    }


app.mount(
    "/",
    StaticFiles(
        directory=STATIC_DIR,
        html=True
    ),
    name="static"
)
