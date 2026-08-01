"""Screen 1 — Momentum First (price/volume only, NSE free data)."""
from __future__ import annotations

from typing import Any, Dict, List, Optional

from swing_indicators import build_indicator_row, clamp

VOL_SPIKE_MULT = 1.5
NEAR_HIGH_PCT = 0.90


def _rank_score(ind: Dict[str, Any], vol_ratio: float, dist_to_high_pct: float) -> float:
    """Continuous 0–100 strength — not binary pass score."""
    score = 0.0
    # Volume strength (0–35): 1.5× → ~18, 3× → 35
    score += clamp((vol_ratio - 1.0) / 2.0 * 35.0, 0, 35)
    # Near highs but not extended (0–30): best around −2% to −8% from high
    # dist_to_high_pct is (close/high - 1)*100, so 0 = at high, −10 = 10% below
    if dist_to_high_pct >= -2:
        score += 18  # at/near high — good but slightly less room
    elif dist_to_high_pct >= -8:
        score += 30  # sweet spot
    elif dist_to_high_pct >= -10:
        score += 22
    else:
        score += 10
    # SMA stack quality (0–25)
    if ind.get("sma20_rising"):
        score += 12
    if ind.get("sma50_rising"):
        score += 8
    sma20, sma50, sma200 = ind.get("sma20"), ind.get("sma50"), ind.get("sma200")
    if sma20 and sma50 and sma200 and sma20 > sma50 > sma200:
        score += 5
    # ATR extension penalty (0–10 remaining): penalize if close >> SMA20 by >2 ATR
    atr14 = ind.get("atr14")
    close = ind.get("close")
    if atr14 and atr14 > 0 and sma20 and close:
        ext = (close - sma20) / atr14
        if ext <= 1.5:
            score += 10
        elif ext <= 2.5:
            score += 5
        # else overextended → 0
    else:
        score += 5
    return round(clamp(score), 1)


def screen_symbol(
    symbol: str,
    name: str,
    rows: List[Dict[str, Any]],
    regime_bullish: bool,
) -> Optional[Dict[str, Any]]:
    if not regime_bullish:
        return None

    ind = build_indicator_row(rows)
    if not ind:
        return None

    close = ind["close"]
    sma20, sma50, sma200 = ind["sma20"], ind["sma50"], ind["sma200"]
    vol, vol_avg = ind["volume"], ind["vol_avg_20"]
    high_range = ind["high_range"]

    if sma20 is None or sma50 is None or close <= sma20 or close <= sma50:
        return None
    if sma200 is None or sma50 <= sma200:
        return None
    if close < NEAR_HIGH_PCT * high_range:
        return None
    if not (vol_avg and vol_avg > 0 and vol >= VOL_SPIKE_MULT * vol_avg):
        return None

    vol_ratio = vol / vol_avg
    dist_pct = (close / high_range - 1) * 100 if high_range else 0
    high_label = "52w high" if ind.get("is_52w_high") else f"{ind.get('high_lookback_days')}d high"

    signals = [
        "Close above SMA20 & SMA50",
        "SMA50 > SMA200 (golden cross regime)",
        f"Within 10% of {high_label} ({dist_pct:+.1f}%)",
        f"Volume {vol_ratio:.1f}× 20d avg",
    ]
    if ind.get("sma20_rising"):
        signals.append("SMA20 rising")

    score = _rank_score(ind, vol_ratio, dist_pct)
    atr14 = ind.get("atr14")
    stop_hint = round(close - 2 * atr14, 2) if atr14 else None

    return {
        "symbol": symbol,
        "name": name,
        "screen": "momentum",
        "close": round(close, 2),
        "score": score,
        "signals": signals,
        "metrics": {
            "sma20": round(sma20, 2) if sma20 else None,
            "sma50": round(sma50, 2) if sma50 else None,
            "sma200": round(sma200, 2) if sma200 else None,
            "high_range": round(high_range, 2),
            "high_lookback_days": float(ind.get("high_lookback_days") or 0),
            "vol_ratio": round(vol_ratio, 2),
            "dist_to_high_pct": round(dist_pct, 2),
            "atr14": round(atr14, 2) if atr14 else None,
            "stop_hint": stop_hint,
        },
        "as_of": ind.get("as_of"),
    }


def screen_universe(
    universe: List[Dict[str, Any]],
    prices: Dict[str, List[Dict[str, Any]]],
    regime_bullish: bool,
) -> List[Dict[str, Any]]:
    hits: List[Dict[str, Any]] = []
    if not regime_bullish:
        return hits
    for u in universe:
        sym = (u.get("ticker") or "").upper()
        if not sym or sym not in prices:
            continue
        hit = screen_symbol(sym, u.get("name") or sym, prices[sym], True)
        if hit:
            hits.append(hit)
    hits.sort(key=lambda h: (h.get("score") or 0, h.get("symbol") or ""), reverse=True)
    return hits
