"""Screen 2 — Sleeping Giant (price/volume only, no fundamentals)."""
from __future__ import annotations

from typing import Any, Dict, List, Optional

from swing_indicators import build_indicator_row, clamp

LOOKBACK = 50
TIGHTNESS_MIN = 0.75
VOL_SPIKE_MULT = 1.5
DORMANT_DAYS = 120
DORMANT_PCT = 0.60


def _rank_score(
    tightness: float,
    vol_ratio: float,
    breakout_pct: float,
    dormant_pct: float,
) -> float:
    score = 0.0
    # Tighter base = better (0.75 → 15, 0.90 → 35)
    score += clamp((tightness - TIGHTNESS_MIN) / (1.0 - TIGHTNESS_MIN) * 35.0, 0, 35)
    # Volume (0–30)
    score += clamp((vol_ratio - 1.0) / 2.0 * 30.0, 0, 30)
    # Fresh breakout strength (0–20): 0–5% above range high preferred
    if 0 < breakout_pct <= 3:
        score += 20
    elif breakout_pct <= 6:
        score += 14
    elif breakout_pct <= 10:
        score += 8
    else:
        score += 3  # already extended past breakout
    # Dormancy depth (0–15)
    score += clamp((dormant_pct - DORMANT_PCT) / (1.0 - DORMANT_PCT) * 15.0, 0, 15)
    return round(clamp(score), 1)


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
    sma200_series = ind.get("sma200_series") or []

    if sma200 is None:
        return None

    # Dormant phase: compare each past close to SMA200 *as of that day*
    end = len(closes) - 1  # exclude today
    start = max(0, end - DORMANT_DAYS)
    dormant_window = list(range(start, end))
    if len(dormant_window) < DORMANT_DAYS // 2:
        return None

    below_count = 0
    compared = 0
    for i in dormant_window:
        s200 = sma200_series[i] if i < len(sma200_series) else None
        if s200 is None:
            continue
        compared += 1
        if closes[i] < s200:
            below_count += 1
    if compared < DORMANT_DAYS // 2:
        return None
    dormant_frac = below_count / compared
    if dormant_frac < DORMANT_PCT:
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

    vol_ratio = vol / vol_avg
    breakout_pct = (close / range_high - 1) * 100
    dormant_pct_display = int(100 * dormant_frac)

    signals = [
        f"Dormant {dormant_pct_display}% of days below then-200 SMA",
        f"Tight base ({tightness:.0%} range ratio)",
        f"Breakout above {LOOKBACK}d high (+{breakout_pct:.1f}%)",
        f"Volume {vol_ratio:.1f}× 20d avg",
        "Near/above 200 SMA",
    ]

    score = _rank_score(tightness, vol_ratio, breakout_pct, dormant_frac)
    atr14 = ind.get("atr14")
    stop_hint = round(close - 2 * atr14, 2) if atr14 else None

    return {
        "symbol": symbol,
        "name": name,
        "screen": "sleeping",
        "close": round(close, 2),
        "score": score,
        "signals": signals,
        "metrics": {
            "sma200": round(sma200, 2),
            "range_tightness": round(tightness, 3),
            "breakout_level": round(range_high, 2),
            "breakout_pct": round(breakout_pct, 2),
            "vol_ratio": round(vol_ratio, 2),
            "dormant_pct": float(dormant_pct_display),
            "atr14": round(atr14, 2) if atr14 else None,
            "stop_hint": stop_hint,
        },
        "as_of": ind.get("as_of"),
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
    hits.sort(key=lambda h: (h.get("score") or 0, h.get("symbol") or ""), reverse=True)
    return hits
