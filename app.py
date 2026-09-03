from pathlib import Path

from fastapi import FastAPI
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

BASE_DIR = Path(__file__).resolve().parent
STATIC_DIR = BASE_DIR / "static"

app = FastAPI()


@app.get("/api/config")
def config():

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
        "client_key": client_key
    }


app.mount(
    "/",
    StaticFiles(
        directory=STATIC_DIR,
        html=True
    ),
    name="static"
)