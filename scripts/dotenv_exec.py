#!/usr/bin/env python3
"""Минимально загружает локальный .env для acceptance subprocess без печати секретов."""

from __future__ import annotations

import os
import re
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
        else:
            # Docker Compose / dotenv допускают комментарий после пробела. Это
            # особенно важно для UUID: комментарий не должен попасть в API.
            value = re.split(r"\s+#", value, maxsplit=1)[0].rstrip()
        values[key] = value
    return values


def main() -> int:
    """Запускает command с dotenv env или безопасно печатает configured/missing status ключа."""

    if len(sys.argv) < 3:
        print("Usage: dotenv_exec.py <.env> [--override KEY=VALUE] (--status KEY | -- <command> ...)", file=sys.stderr)
        return 2
    values = load(Path(sys.argv[1]))
    arguments = sys.argv[2:]
    while arguments and arguments[0] == "--override":
        if len(arguments) < 2 or "=" not in arguments[1]:
            print("--override requires KEY=VALUE", file=sys.stderr)
            return 2
        key, value = arguments[1].split("=", 1)
        values[key] = value
        arguments = arguments[2:]
    if not arguments:
        return 2
    if arguments[0] == "--status":
        key = arguments[1]
        print("configured" if values.get(key, "").strip() and values[key] != "replace_me" else "missing")
        return 0
    if arguments[0] == "--equals":
        return 0 if values.get(arguments[1], "") == arguments[2] else 1
    command = arguments[1:] if arguments[0] == "--" else arguments
    if not command:
        return 2
    environment = os.environ.copy()
    environment.update(values)
    os.execvpe(command[0], command, environment)
    return 127


if __name__ == "__main__":
    raise SystemExit(main())
