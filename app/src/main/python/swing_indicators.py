"""Technical indicators for Swing tab — pure OHLCV, no external APIs."""
from __future__ import annotations

from typing import Any, Dict, List, Optional


def sma(values: List[float], period: int) -> Optional[float]:
    if len(values) < period:
        return None
    window = values[-period:]
    return sum(window) / period


def sma_series(values: List[float], period: int) -> List[Optional[float]]:
    """SMA at each index (None until enough history)."""
    out: List[Optional[float]] = [None] * len(values)
    if period <= 0:
        return out
    running = 0.0
    for i, v in enumerate(values):
        running += v
        if i >= period:
            running -= values[i - period]
        if i >= period - 1:
            out[i] = running / period
    return out


def atr(highs: List[float], lows: List[float], closes: List[float], period: int = 14) -> Optional[float]:
    if len(closes) < period + 1:
        return None
    trs: List[float] = []
    for i in range(1, len(closes)):
        h, l, pc = highs[i], lows[i], closes[i - 1]
        trs.append(max(h - l, abs(h - pc), abs(l - pc)))
    if len(trs) < period:
        return None
    return sum(trs[-period:]) / period


def build_indicator_row(rows: List[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    """Compute latest-day indicators from ascending OHLCV rows."""
    if len(rows) < 60:
        return None
    closes = [float(r["close"]) for r in rows]
    highs = [float(r["high"]) for r in rows]
    lows = [float(r["low"]) for r in rows]
    volumes = [float(r.get("volume") or 0) for r in rows]
    dates = [str(r.get("date") or "") for r in rows]

    sma20 = sma(closes, 20)
    sma50 = sma(closes, 50)
    sma200 = sma(closes, 200) if len(closes) >= 200 else None
    vol_avg_20 = sma(volumes, 20)
    lookback_high = min(252, len(highs))
    high_range = max(highs[-lookback_high:])
    atr14 = atr(highs, lows, closes, 14)
    is_52w = lookback_high >= 252

    # SMA slopes (20d change) for ranking
    sma20_prev = sma(closes[:-5], 20) if len(closes) >= 25 else None
    sma50_prev = sma(closes[:-5], 50) if len(closes) >= 55 else None

    return {
        "close": closes[-1],
        "volume": volumes[-1],
        "sma20": sma20,
        "sma50": sma50,
        "sma200": sma200,
        "vol_avg_20": vol_avg_20,
        "high_range": high_range,
        "high_lookback_days": lookback_high,
        "is_52w_high": is_52w,
        "atr14": atr14,
        "as_of": dates[-1] if dates else None,
        "sma20_rising": (
            sma20 is not None and sma20_prev is not None and sma20 > sma20_prev
        ),
        "sma50_rising": (
            sma50 is not None and sma50_prev is not None and sma50 > sma50_prev
        ),
        "closes": closes,
        "highs": highs,
        "lows": lows,
        "volumes": volumes,
        "sma200_series": sma_series(closes, 200) if len(closes) >= 200 else [],
    }


def market_regime(index_rows: List[Dict[str, Any]]) -> Dict[str, Any]:
    """Nifty 50 vs 200 SMA — three states: bullish / bearish / insufficient."""
    if len(index_rows) < 200:
        return {
            "state": "insufficient",
            "bullish": False,
            "label": "Insufficient data — need ~200 Nifty days (run again after sync)",
            "close": float(index_rows[-1]["close"]) if index_rows else None,
            "sma200": None,
            "as_of": str(index_rows[-1].get("date") or "") if index_rows else None,
        }
    closes = [float(r["close"]) for r in index_rows]
    dates = [str(r.get("date") or "") for r in index_rows]
    sma200 = sum(closes[-200:]) / 200
    close = closes[-1]
    bullish = close > sma200
    return {
        "state": "bullish" if bullish else "bearish",
        "bullish": bullish,
        "label": (
            f"Bullish (Nifty {close:.0f} > 200 SMA {sma200:.0f})"
            if bullish
            else f"Bearish (Nifty {close:.0f} ≤ 200 SMA {sma200:.0f})"
        ),
        "close": round(close, 2),
        "sma200": round(sma200, 2),
        "as_of": dates[-1] if dates else None,
    }


def clamp(x: float, lo: float = 0.0, hi: float = 100.0) -> float:
    return max(lo, min(hi, x))
