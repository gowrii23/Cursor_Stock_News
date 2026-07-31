"""Progress callbacks from Python pipeline to Kotlin UI."""
from __future__ import annotations

from typing import Any, Optional


def report(progress_cb: Any, percent: int, message: str) -> None:
    if progress_cb is None:
        return
    try:
        progress_cb.onProgress(int(max(0, min(100, percent))), str(message))
    except Exception:
        pass
