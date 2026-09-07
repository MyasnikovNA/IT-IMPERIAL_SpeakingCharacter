"""Регрессия для dotenv-парсинга, используемого native acceptance runner-ом."""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import dotenv_exec


class DotenvExecTest(unittest.TestCase):
    """Проверяет, что inline-комментарии не попадают в provider credentials и UUID."""

    def test_unquoted_value_excludes_inline_comment(self) -> None:
        """Сохраняет UUID до пробела с `#`, как это делает стандартный dotenv формат."""

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env"
            path.write_text("SIMLI_FACE_ID=d2a5c7c6-fed9-4f55-bcb3-062f7cd20103 # Женщина\n", encoding="utf-8")

            values = dotenv_exec.load(path)

        self.assertEqual(values["SIMLI_FACE_ID"], "d2a5c7c6-fed9-4f55-bcb3-062f7cd20103")

    def test_hash_inside_quoted_value_is_preserved(self) -> None:
        """Не обрезает символ `#`, когда он является частью явно quoted значения."""

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env"
            path.write_text('LABEL="Сценарий #1"\n', encoding="utf-8")

            values = dotenv_exec.load(path)

        self.assertEqual(values["LABEL"], "Сценарий #1")


if __name__ == "__main__":
    unittest.main()
