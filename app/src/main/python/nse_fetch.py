"""NSE bhavcopy + index CSV price fetch with SQLite-backed history."""
from __future__ import annotations

import io
import logging
from datetime import date, datetime, timedelta
from typing import Any, Dict, List, Optional, Set, Tuple

import pandas as pd
import requests

logger = logging.getLogger(__name__)

BHAVCOPY_URL = (
    "https://nsearchives.nseindia.com/products/content/sec_bhavdata_full_{ddmmyyyy}.csv"
)
INDEX_URL = (
    "https://nsearchives.nseindia.com/content/indices/ind_close_all_{ddmmyyyy}.csv"
)
NIFTY_INDEX_NAME = "NIFTY 50"
MIN_TRADING_DAYS = 40
TARGET_TRADING_DAYS = 280
MAX_DOWNLOADS_PER_RUN = 55
MIN_ROWS_FOR_FRAME = 20

_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    ),
    "Accept": "text/csv, text/plain, */*",
}


def fetch_ohlc_nse(
    tickers: List[str],
    db: Any,
    include_index: bool = True,
) -> Tuple[Dict[str, pd.DataFrame], Dict[str, str]]:
    """
    Sync NSE bhavcopy history into SQLite and return ticker -> OHLC DataFrame.
    Returns (prices, health) where health maps source -> ok|fail|partial.
    """
    health: Dict[str, str] = {"bhavcopy": "fail"}
    try:
        added = _sync_history(db, tickers, include_index=include_index)
        prices = _build_frames_from_db(db, tickers, include_index=include_index)
        have = len([t for t in tickers if t in prices])
        if include_index and "NIFTY50" in prices:
            if have >= max(20, len(tickers) // 3):
                health["bhavcopy"] = "ok"
            elif have > 0:
                health["bhavcopy"] = "partial"
        elif have > 0:
            health["bhavcopy"] = "partial"
        if added:
            logger.info("NSE sync added %d trading days", added)
    except Exception as e:
        logger.warning("NSE price sync failed: %s", e)
        prices = _build_frames_from_db(db, tickers, include_index=include_index)
        if prices:
            health["bhavcopy"] = "partial"
    return prices, health


def _sync_history(db: Any, tickers: List[str], include_index: bool) -> int:
    """Download missing bhavcopy/index files up to per-run cap."""
    ticker_set = {t.upper() for t in tickers}
    min_tickers_per_day = max(15, int(len(ticker_set) * 0.5))
    existing_dates = set(db.get_cached_price_dates())
    complete_dates = {
        d for d in existing_dates
        if db.count_tickers_for_date(d) >= min_tickers_per_day
    }
    target_days = int(db.get_setting("price_backfill_days", TARGET_TRADING_DAYS))
    target_days = max(MIN_TRADING_DAYS, min(target_days, 400))
    need = max(0, target_days - len(complete_dates))

    added = 0
    scanned = 0
    today = date.today()
    d = today
    while scanned < 420 and added < max(MAX_DOWNLOADS_PER_RUN, need):
        if d.weekday() >= 5:
            d -= timedelta(days=1)
            scanned += 1
            continue
        iso = d.isoformat()
        incomplete = (
            iso not in complete_dates
            or db.count_tickers_for_date(iso) < min_tickers_per_day
        )
        if incomplete:
            bhav_rows, index_close = _download_day(d)
            if bhav_rows:
                filtered = [r for r in bhav_rows if r["ticker"] in ticker_set]
                if filtered:
                    db.upsert_price_history(filtered)
                if include_index and index_close is not None:
                    db.upsert_index_history(iso, index_close)
                if filtered:
                    complete_dates.add(iso)
                    added += 1
                if len(complete_dates) >= target_days and added >= 1:
                    break
        d -= timedelta(days=1)
        scanned += 1
    return added


def _download_day(d: date) -> Tuple[List[Dict[str, Any]], Optional[float]]:
    ddmmyyyy = d.strftime("%d%m%Y")
    bhav_rows: List[Dict[str, Any]] = []
    index_close: Optional[float] = None

    bhav_url = BHAVCOPY_URL.format(ddmmyyyy=ddmmyyyy)
    try:
        resp = requests.get(bhav_url, headers=_HEADERS, timeout=25)
        if resp.status_code == 200 and len(resp.content) > 500:
            bhav_rows = _parse_bhavcopy(resp.text)
    except Exception as e:
        logger.warning("bhavcopy %s failed: %s", ddmmyyyy, e)

    index_url = INDEX_URL.format(ddmmyyyy=ddmmyyyy)
    try:
        resp = requests.get(index_url, headers=_HEADERS, timeout=25)
        if resp.status_code == 200 and len(resp.content) > 100:
            index_close = _parse_nifty_close(resp.text)
    except Exception as e:
        logger.warning("index %s failed: %s", ddmmyyyy, e)

    return bhav_rows, index_close


def _parse_bhavcopy(text: str) -> List[Dict[str, Any]]:
    rows: List[Dict[str, Any]] = []
    try:
        df = pd.read_csv(io.StringIO(text))
        df.columns = [str(c).strip().upper() for c in df.columns]
        if "SYMBOL" not in df.columns or "CLOSE_PRICE" not in df.columns:
            return rows
        series_col = "SERIES" if "SERIES" in df.columns else None
        date_col = "DATE1" if "DATE1" in df.columns else "DATE"
        for _, row in df.iterrows():
            if series_col and str(row.get(series_col, "EQ")).strip().upper() != "EQ":
                continue
            sym = str(row.get("SYMBOL", "")).strip().upper()
            if not sym:
                continue
            close = _to_float(row.get("CLOSE_PRICE"))
            if close is None:
                continue
            dt = _parse_bhav_date(row.get(date_col))
            rows.append(
                {
                    "ticker": sym,
                    "date": dt,
                    "open": _to_float(row.get("OPEN_PRICE")) or close,
                    "high": _to_float(row.get("HIGH_PRICE")) or close,
                    "low": _to_float(row.get("LOW_PRICE")) or close,
                    "close": close,
                    "volume": _to_float(row.get("TTL_TRD_QNTY")) or 0.0,
                }
            )
    except Exception as e:
        logger.warning("bhavcopy parse error: %s", e)
    return rows


def _parse_nifty_close(text: str) -> Optional[float]:
    try:
        df = pd.read_csv(io.StringIO(text))
        df.columns = [str(c).strip() for c in df.columns]
        name_col = "Index Name" if "Index Name" in df.columns else df.columns[0]
        close_col = None
        for c in df.columns:
            if "closing" in c.lower():
                close_col = c
                break
        if close_col is None:
            return None
        mask = df[name_col].astype(str).str.upper().str.contains("NIFTY 50", na=False)
        subset = df[mask]
        if subset.empty:
            return None
        return _to_float(subset.iloc[0][close_col])
    except Exception as e:
        logger.warning("index parse error: %s", e)
        return None


def _build_frames_from_db(
    db: Any,
    tickers: List[str],
    include_index: bool = True,
) -> Dict[str, pd.DataFrame]:
    out: Dict[str, pd.DataFrame] = {}
    for t in tickers:
        rows = db.get_price_history(t)
        if len(rows) >= MIN_ROWS_FOR_FRAME:
            out[t] = _rows_to_frame(rows)
    if include_index:
        idx_rows = db.get_index_history()
        if len(idx_rows) >= MIN_ROWS_FOR_FRAME:
            out["NIFTY50"] = _rows_to_frame(idx_rows, is_index=True)
    return out


def _rows_to_frame(rows: List[Dict[str, Any]], is_index: bool = False) -> pd.DataFrame:
    if not rows:
        return pd.DataFrame()
    df = pd.DataFrame(rows)
    df["date"] = pd.to_datetime(df["date"])
    df = df.set_index("date").sort_index()
    if is_index:
        close = df["close"].astype(float)
        return pd.DataFrame(
            {
                "Open": close,
                "High": close,
                "Low": close,
                "Close": close,
                "Volume": 0.0,
            },
            index=df.index,
        )
    keep = ["open", "high", "low", "close", "volume"]
    df = df[keep].astype(float)
    df.columns = ["Open", "High", "Low", "Close", "Volume"]
    return df


def _parse_bhav_date(value: Any) -> str:
    if value is None:
        return date.today().isoformat()
    s = str(value).strip()
    for fmt in ("%d-%b-%Y", "%d-%B-%Y", "%Y-%m-%d", "%d-%m-%Y"):
        try:
            return datetime.strptime(s, fmt).date().isoformat()
        except Exception:
            continue
    return date.today().isoformat()


def _to_float(value: Any) -> Optional[float]:
    try:
        if value is None or (isinstance(value, float) and pd.isna(value)):
            return None
        s = str(value).replace(",", "").strip()
        if not s or s == "-":
            return None
        return float(s)
    except Exception:
        return None
