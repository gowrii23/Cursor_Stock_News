"""Technical indicators for Swing tab — pure OHLCV, no external APIs."""
from __future__ import annotations

from typing import Any, Dict, List, Optional


def sma(values: List[float], period: int) -> Optional[float]:
    if len(values) < period:
        return None
    window = values[-period:]
    return sum(window) / period


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

    sma20 = sma(closes, 20)
    sma50 = sma(closes, 50)
    sma200 = sma(closes, 200) if len(closes) >= 200 else None
    vol_avg_20 = sma(volumes, 20)
    high_52w = max(highs[-252:]) if len(highs) >= 252 else max(highs)
    atr14 = atr(highs, lows, closes, 14)

    return {
        "close": closes[-1],
        "volume": volumes[-1],
        "sma20": sma20,
        "sma50": sma50,
        "sma200": sma200,
        "vol_avg_20": vol_avg_20,
        "high_52w": high_52w,
        "atr14": atr14,
        "closes": closes,
        "highs": highs,
        "lows": lows,
        "volumes": volumes,
    }


def market_regime(index_rows: List[Dict[str, Any]]) -> Dict[str, Any]:
    """Nifty 50 vs 200 SMA — gate for momentum screen."""
    if len(index_rows) < 200:
        return {
            "bullish": False,
            "label": "Unknown — need more index history",
            "close": index_rows[-1]["close"] if index_rows else None,
            "sma200": None,
        }
    closes = [float(r["close"]) for r in index_rows]
    sma200 = sum(closes[-200:]) / 200
    close = closes[-1]
    bullish = close > sma200
    return {
        "bullish": bullish,
        "label": "Bullish (Nifty > 200 SMA)" if bullish else "Bearish (Nifty ≤ 200 SMA)",
        "close": round(close, 2),
        "sma200": round(sma200, 2),
    }
