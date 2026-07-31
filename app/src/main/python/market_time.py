"""IST market-close helpers for EOD news timing."""
from __future__ import annotations

from datetime import date, datetime, time, timezone
from typing import Optional
from zoneinfo import ZoneInfo

IST = ZoneInfo("Asia/Kolkata")
MARKET_CLOSE = time(15, 30)


def timing_vs_market_close(
    published_at: Optional[str],
    trade_date: Optional[str] = None,
) -> str:
    """
    Returns:
      before_close — headline published on trade date before 15:30 IST
      after_close  — headline published on trade date at/after 15:30 IST
      unknown      — cannot determine
    """
    if not published_at:
        return "unknown"
    try:
        dt = datetime.fromisoformat(published_at.replace("Z", "+00:00"))
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        dt_ist = dt.astimezone(IST)
    except Exception:
        return "unknown"

    if trade_date:
        try:
            td = date.fromisoformat(trade_date[:10])
        except Exception:
            td = dt_ist.date()
    else:
        td = dt_ist.date()

    if dt_ist.date() != td:
        return "unknown"
    return "before_close" if dt_ist.time() < MARKET_CLOSE else "after_close"
