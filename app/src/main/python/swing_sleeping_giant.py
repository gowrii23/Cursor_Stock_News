"""Screen 2 — Sleeping Giant (price/volume only, no fundamentals)."""
from __future__ import annotations

from typing import Any, Dict, List, Optional

from swing_indicators import build_indicator_row

LOOKBACK = 50
TIGHTNESS_MIN = 0.75
VOL_SPIKE_MULT = 1.5
DORMANT_DAYS = 120
DORMANT_PCT = 0.60


def screen_symbol(
    symbol: str,
    name: str,
    rows: List[Dict[str, Any]],
) -> Optional[Dict[str, Any]]:
    ind = build_indicator_row(rows)
    if not ind or len(ind["closes"]) < max(LOOKBACK + 5, DORMANT_DAYS):
        return None

    closes, highs, lows = ind["closes"], ind["highs"], ind["lows"]
    close = ind["close"]
    sma200 = ind["sma200"]
    vol, vol_avg = ind["volume"], ind["vol_avg_20"]

    if sma200 is None:
        return None

    # Dormant phase: mostly below 200 SMA over prior window
    dormant_window = closes[-(DORMANT_DAYS + 1):-1]
    if len(dormant_window) < DORMANT_DAYS:
        return None
    below_count = sum(1 for c in dormant_window if c < sma200)
    if below_count / len(dormant_window) < DORMANT_PCT:
        return None

    window_highs = highs[-LOOKBACK:-1]
    window_lows = lows[-LOOKBACK:-1]
    if len(window_highs) < LOOKBACK - 1:
        return None

    range_high = max(window_highs)
    range_low = min(window_lows)
    if range_high <= 0:
        return None
    tightness = range_low / range_high
    if tightness < TIGHTNESS_MIN:
        return None

    if close <= range_high:
        return None

    if not (vol_avg and vol_avg > 0 and vol >= VOL_SPIKE_MULT * vol_avg):
        return None

    if close < sma200 * 0.98:
        return None

    signals = [
        f"Dormant {int(100 * below_count / len(dormant_window))}% days below 200 SMA",
        f"Tight base ({tightness:.0%} range ratio)",
        f"Breakout above {LOOKBACK}d high",
        f"Volume {(vol / vol_avg):.1f}× 20d avg",
        "Near/above 200 SMA",
    ]
    score = min(100.0, 20 * len(signals))

    return {
        "symbol": symbol,
        "name": name,
        "screen": "sleeping",
        "close": round(close, 2),
        "score": round(score, 1),
        "signals": signals,
        "metrics": {
            "sma200": round(sma200, 2),
            "range_tightness": round(tightness, 3),
            "breakout_level": round(range_high, 2),
            "vol_ratio": round(vol / vol_avg, 2) if vol_avg else None,
        },
    }


def screen_universe(
    universe: List[Dict[str, Any]],
    prices: Dict[str, List[Dict[str, Any]]],
) -> List[Dict[str, Any]]:
    hits: List[Dict[str, Any]] = []
    for u in universe:
        sym = (u.get("ticker") or "").upper()
        if not sym or sym not in prices:
            continue
        hit = screen_symbol(sym, u.get("name") or sym, prices[sym])
        if hit:
            hits.append(hit)
    hits.sort(key=lambda h: h.get("score") or 0, reverse=True)
    return hits
