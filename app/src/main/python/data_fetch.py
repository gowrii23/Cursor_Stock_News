"""Price data fetch via Yahoo chart API (pure requests — Chaquopy-safe)."""
from __future__ import annotations

import logging
from datetime import datetime
from typing import Dict, List, Tuple

import numpy as np
import pandas as pd
import requests

logger = logging.getLogger(__name__)

INDEX_TICKER = "^NSEI"
YAHOO_CHART = "https://query1.finance.yahoo.com/v8/finance/chart/{symbol}"

_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    ),
}


def nse_symbol(ticker: str) -> str:
    return f"{ticker}.NS"


def fetch_ohlc(
    tickers: List[str],
    period: str = "3y",
    include_index: bool = True,
) -> Dict[str, pd.DataFrame]:
    """
    Fetch daily OHLC for tickers (+ Nifty 50 index).
    Returns dict ticker -> DataFrame Open/High/Low/Close/Volume.
    """
    symbols = [(t, nse_symbol(t)) for t in tickers]
    if include_index:
        symbols.append(("NIFTY50", INDEX_TICKER))

    out: Dict[str, pd.DataFrame] = {}
    for logical, sym in symbols:
        try:
            df = _download_symbol(sym, period=period)
            if df is not None and not df.empty:
                out[logical] = df
        except Exception as ex:
            logger.warning("failed %s: %s", sym, ex)
    return out


def _download_symbol(symbol: str, period: str = "3y") -> pd.DataFrame:
    params = {
        "range": period,
        "interval": "1d",
        "events": "div,splits",
        "includeAdjustedClose": "true",
    }
    url = YAHOO_CHART.format(symbol=requests.utils.quote(symbol, safe=""))
    resp = requests.get(url, params=params, headers=_HEADERS, timeout=30)
    resp.raise_for_status()
    payload = resp.json()
    result = (payload.get("chart") or {}).get("result") or []
    if not result:
        err = (payload.get("chart") or {}).get("error")
        raise RuntimeError(f"no chart data: {err}")
    node = result[0]
    ts = node.get("timestamp") or []
    quote = ((node.get("indicators") or {}).get("quote") or [{}])[0]
    adj = ((node.get("indicators") or {}).get("adjclose") or [{}])
    adj_close = adj[0].get("adjclose") if adj else None

    closes = adj_close or quote.get("close") or []
    df = pd.DataFrame(
        {
            "Open": quote.get("open") or closes,
            "High": quote.get("high") or closes,
            "Low": quote.get("low") or closes,
            "Close": closes,
            "Volume": quote.get("volume") or [0] * len(ts),
        },
        index=pd.to_datetime(ts, unit="s"),
    )
    return _normalize_df(df)


def _normalize_df(df: pd.DataFrame) -> pd.DataFrame:
    if df is None or df.empty:
        return pd.DataFrame()
    keep = [c for c in ["Open", "High", "Low", "Close", "Volume"] if c in df.columns]
    if "Close" not in keep:
        return pd.DataFrame()
    df = df[keep].dropna(subset=["Close"])
    df.index = pd.to_datetime(df.index).tz_localize(None).normalize()
    df = df[~df.index.duplicated(keep="last")].sort_index()
    return df


def daily_returns(close: pd.Series) -> pd.Series:
    return close.pct_change().dropna()


def align_returns(
    stock_close: pd.Series, index_close: pd.Series
) -> Tuple[pd.Series, pd.Series]:
    s = daily_returns(stock_close)
    i = daily_returns(index_close)
    joined = pd.concat([s.rename("s"), i.rename("i")], axis=1).dropna()
    return joined["s"], joined["i"]


def generate_demo_prices(
    tickers: List[str], days: int = 400, seed: int = 42
) -> Dict[str, pd.DataFrame]:
    """Synthetic OHLC so the APK UI works offline / before first live fetch."""
    rng = np.random.default_rng(seed)
    end = pd.Timestamp(datetime.utcnow().date())
    dates = pd.bdate_range(end=end, periods=days)

    idx_rets = rng.normal(0.0004, 0.009, size=days)
    idx_close = 22000 * np.cumprod(1 + idx_rets)
    out: Dict[str, pd.DataFrame] = {
        "NIFTY50": pd.DataFrame(
            {
                "Open": idx_close,
                "High": idx_close * 1.005,
                "Low": idx_close * 0.995,
                "Close": idx_close,
                "Volume": rng.integers(1e8, 3e8, size=days),
            },
            index=dates,
        )
    }

    for i, t in enumerate(tickers):
        beta = 0.5 + (i % 10) * 0.08
        alpha = rng.normal(0.0002, 0.0003)
        idio = rng.normal(0, 0.012, size=days)
        if i % 17 == 0:
            idio[-1] = -0.045 - (i % 5) * 0.005
        rets = alpha + beta * idx_rets + idio
        px0 = 200 + (i * 37) % 1800
        close = px0 * np.cumprod(1 + rets)
        out[t] = pd.DataFrame(
            {
                "Open": close,
                "High": close * 1.01,
                "Low": close * 0.99,
                "Close": close,
                "Volume": rng.integers(1e5, 5e6, size=days),
            },
            index=dates,
        )
    return out
