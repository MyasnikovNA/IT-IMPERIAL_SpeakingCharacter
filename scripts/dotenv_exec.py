#!/usr/bin/env python3
"""Минимально загружает локальный .env для acceptance subprocess без печати секретов."""

from __future__ import annotations

import os
import sys
from pathlib import Path


def load(path: Path) -> dict[str, str]:
    """Разбирает обычные KEY=value строки, включая некавыченные значения с пробелами."""

    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip().removeprefix("export ").strip()
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
            value = value[1:-1]
        values[key] = value
    return values


def main() -> int:
    """Запускает command с dotenv env или безопасно печатает configured/missing status ключа."""

    if len(sys.argv) < 3:
        print("Usage: dotenv_exec.py <.env> (--status KEY | -- <command> ...)", file=sys.stderr)
        return 2
    values = load(Path(sys.argv[1]))
    if sys.argv[2] == "--status":
        key = sys.argv[3]
        print("configured" if values.get(key, "").strip() and values[key] != "replace_me" else "missing")
        return 0
    if sys.argv[2] == "--equals":
        return 0 if values.get(sys.argv[3], "") == sys.argv[4] else 1
    command = sys.argv[3:] if sys.argv[2] == "--" else sys.argv[2:]
    if not command:
        return 2
    environment = os.environ.copy()
    environment.update(values)
    os.execvpe(command[0], command, environment)
    return 127


if __name__ == "__main__":
    raise SystemExit(main())
