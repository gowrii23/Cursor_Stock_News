"""Screen 1 — Momentum First (price/volume only, NSE free data)."""
from __future__ import annotations

from typing import Any, Dict, List, Optional

from swing_indicators import build_indicator_row

VOL_SPIKE_MULT = 1.5
NEAR_HIGH_PCT = 0.90


def screen_symbol(
    symbol: str,
    name: str,
    rows: List[Dict[str, Any]],
    regime_bullish: bool,
) -> Optional[Dict[str, Any]]:
    ind = build_indicator_row(rows)
    if not ind:
        return None

    close = ind["close"]
    sma20, sma50, sma200 = ind["sma20"], ind["sma50"], ind["sma200"]
    vol, vol_avg = ind["volume"], ind["vol_avg_20"]
    high_52w = ind["high_52w"]

    signals: List[str] = []
    score = 0.0

    if not regime_bullish:
        return None

    if sma20 is None or sma50 is None or close <= sma20 or close <= sma50:
        return None
    signals.append("Close above SMA20 & SMA50")
    score += 25

    if sma200 is None or sma50 <= sma200:
        return None
    signals.append("SMA50 > SMA200 (golden cross regime)")
    score += 25

    if close < NEAR_HIGH_PCT * high_52w:
        return None
    pct = (close / high_52w - 1) * 100 if high_52w else 0
    signals.append(f"Within {100 - int(NEAR_HIGH_PCT * 100)}% of 52w high ({pct:+.1f}%)")
    score += 25

    if vol_avg and vol_avg > 0 and vol >= VOL_SPIKE_MULT * vol_avg:
        ratio = vol / vol_avg
        signals.append(f"Volume {ratio:.1f}× 20d avg")
        score += 25
    else:
        return None

    return {
        "symbol": symbol,
        "name": name,
        "screen": "momentum",
        "close": round(close, 2),
        "score": round(score, 1),
        "signals": signals,
        "metrics": {
            "sma20": round(sma20, 2) if sma20 else None,
            "sma50": round(sma50, 2) if sma50 else None,
            "sma200": round(sma200, 2) if sma200 else None,
            "high_52w": round(high_52w, 2),
            "vol_ratio": round(vol / vol_avg, 2) if vol_avg else None,
        },
    }


def screen_universe(
    universe: List[Dict[str, Any]],
    prices: Dict[str, List[Dict[str, Any]]],
    regime_bullish: bool,
) -> List[Dict[str, Any]]:
    hits: List[Dict[str, Any]] = []
    for u in universe:
        sym = (u.get("ticker") or "").upper()
        if not sym or sym not in prices:
            continue
        hit = screen_symbol(sym, u.get("name") or sym, prices[sym], regime_bullish)
        if hit:
            hits.append(hit)
    hits.sort(key=lambda h: h.get("score") or 0, reverse=True)
    return hits
