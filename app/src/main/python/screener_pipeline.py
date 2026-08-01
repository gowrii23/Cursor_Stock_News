"""Screener.in capture processing — Layer 1/2/3 pipeline for Gowri Screener tab."""
from __future__ import annotations

import json
import logging
from datetime import datetime
from typing import Any, Dict, List, Optional

from db import Database
from nse_fetch import fetch_ohlc_nse
from progress_report import report
from screener_engine import process_rows

logger = logging.getLogger(__name__)

DEFAULT_SCREEN_URL = "https://www.screener.in/screens/3835709/cursor/"


def process_screener_capture(
    rows_json: str,
    db_path: Optional[str] = None,
    source_url: str = DEFAULT_SCREEN_URL,
    progress_cb: Any = None,
) -> str:
    """Process WebView-captured rows through L1/L2/L3 and persist."""
    started = datetime.utcnow().isoformat()
    db = Database(db_path)
    try:
        report(progress_cb, 52, "Parsing captured rows…")
        rows = json.loads(rows_json)
        if not isinstance(rows, list):
            return json.dumps({"status": "error", "message": "Invalid rows payload"})

        report(progress_cb, 55, f"Loaded {len(rows)} raw rows from screener.in")

        # Quick L1/L2 pass first to find shortlist symbols
        report(progress_cb, 58, "Running Layer 1 mandatory filters…")
        preview = process_rows(rows, run_layer3=False, db=None)
        report(
            progress_cb,
            65,
            f"Layer 1: {preview['passed_l1']}/{preview['total_raw']} passed",
        )

        shortlist_syms = [
            s["symbol"]
            for s in preview["stocks"]
            if (s.get("score_total") or 0) >= 60 and s.get("symbol")
        ]
        if shortlist_syms:
            report(
                progress_cb,
                68,
                f"Fetching NSE prices for {len(shortlist_syms)} shortlist symbols (Layer 3)…",
            )
            try:
                fetch_ohlc_nse(shortlist_syms[:40], db, include_index=True, progress_cb=progress_cb)
            except Exception as e:
                logger.warning("Shortlist price fetch: %s", e)

        report(progress_cb, 85, "Running Layer 2 scoring + Layer 3 technical overlay…")
        result = process_rows(rows, run_layer3=True, db=db)

        meta = {
            "scanned_at": started,
            "source_url": source_url,
            "total_raw": result["total_raw"],
            "passed_l1": result["passed_l1"],
            "high_count": result["high_conviction"],
            "watch_count": result["watchlist"],
            "message": (
                f"raw={result['total_raw']} l1={result['passed_l1']} "
                f"high={result['high_conviction']} watch={result['watchlist']}"
            ),
        }
        db.save_screener_scan(meta, result["stocks"])

        report(
            progress_cb,
            100,
            f"Done — {result['high_conviction']} high conviction, {result['watchlist']} watchlist",
        )
        return json.dumps(
            {
                "status": "ok",
                "scan": meta,
                "high_count": result["high_conviction"],
                "watch_count": result["watchlist"],
                "passed_l1": result["passed_l1"],
                "total_raw": result["total_raw"],
            }
        )
    except Exception as e:
        logger.exception("screener process failed")
        return json.dumps({"status": "error", "message": str(e)})
    finally:
        db.close()


def get_screener_dashboard_json(db_path: Optional[str] = None) -> str:
    db = Database(db_path)
    try:
        scan = db.latest_screener_scan()
        stocks = db.get_screener_stocks() if scan else []
        return json.dumps({"scan": scan, "stocks": stocks}, default=str)
    finally:
        db.close()


def get_screener_detail_json(symbol: str, db_path: Optional[str] = None) -> str:
    db = Database(db_path)
    try:
        stock = db.get_screener_stock(symbol.upper())
        if not stock:
            return json.dumps({"status": "error", "message": "Not found"})
        return json.dumps({"status": "ok", "stock": stock})
    finally:
        db.close()


def set_screener_verified_json(symbol: str, verified: bool, db_path: Optional[str] = None) -> str:
    db = Database(db_path)
    try:
        db.set_screener_verified(symbol, verified)
        return json.dumps({"status": "ok"})
    finally:
        db.close()
