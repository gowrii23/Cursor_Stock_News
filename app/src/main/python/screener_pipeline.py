"""Screener.in capture processing — Layer 1/2/3 pipeline for Gowri Screener tab."""
from __future__ import annotations

import json
import logging
import os
from datetime import datetime
from typing import Any, Dict, List, Optional

from blueprint_tagger import load_blueprint_map, tags_for
from db import Database
from nse_fetch import fetch_ohlc_nse
from pattas_engine import find_pattas_candidates
from pipeline import _asset_path, load_json_asset
from progress_report import report
from screener_engine import pick_top_review, process_rows

logger = logging.getLogger(__name__)

DEFAULT_SCREEN_URL = "https://www.screener.in/screens/3835709/cursor/"


def _load_blueprint_map() -> Dict[str, List[str]]:
    blueprint_path = _asset_path("blueprint_tags.json")
    alt_bp = os.path.join(os.environ.get("HOME", ""), "blueprint_tags.json")
    bp_map = load_blueprint_map(
        blueprint_path if os.path.exists(blueprint_path) else alt_bp
    )
    if not bp_map:
        loaded = load_json_asset("blueprint_tags.json", {})
        if isinstance(loaded, dict):
            bp_map = {str(k).upper(): list(v) for k, v in loaded.items()}
    return bp_map


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
        bp_map = _load_blueprint_map()

        # Quick L1/L2 pass first to find shortlist symbols
        report(progress_cb, 58, "Running Layer 1 mandatory filters…")
        preview = process_rows(rows, run_layer3=False, db=None, blueprint_map=bp_map)
        report(
            progress_cb,
            65,
            (
                f"Layer 1: {preview['passed_l1']}/{preview['total_raw']} passed "
                f"({preview.get('incomplete', 0)} incomplete data)"
            ),
        )

        shortlist_syms = [
            s["symbol"]
            for s in preview["stocks"]
            if (s.get("score_total") or 0) >= 50 and s.get("symbol")
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
        result = process_rows(rows, run_layer3=True, db=db, blueprint_map=bp_map)

        top_review = pick_top_review(result.get("all_rows") or result["stocks"])
        meta = {
            "scanned_at": started,
            "source_url": source_url,
            "total_raw": result["total_raw"],
            "passed_l1": result["passed_l1"],
            "high_count": result["high_conviction"],
            "watch_count": result["watchlist"],
            "low_count": result["low_conviction"],
            "incomplete_count": result.get("incomplete") or 0,
            "blueprint_count": result.get("blueprint_count") or 0,
            "top_review": top_review,
            "message": (
                f"raw={result['total_raw']} l1={result['passed_l1']} "
                f"incomplete={result.get('incomplete', 0)} "
                f"high={result['high_conviction']} watch={result['watchlist']} "
                f"low={result['low_conviction']} "
                f"blueprint={result.get('blueprint_count', 0)}"
            ),
        }
        db.save_screener_scan(meta, result["stocks"])

        # Pattas TO BE candidates from full universe (free once screener data exists)
        try:
            pattas_syms = {s["symbol"] for s in db.get_pattas_symbols()}
            raw_rows = result.get("all_rows") or result["stocks"]
            candidates = find_pattas_candidates(raw_rows, pattas_syms, min_pillars=3)
            db.save_pattas_candidates(candidates)
        except Exception as e:
            logger.warning("Pattas candidate discovery: %s", e)

        report(
            progress_cb,
            100,
            (
                f"Done — {result['high_conviction']} high (70+) · "
                f"{result['watchlist']} watch · {result['low_conviction']} low · "
                f"{result.get('blueprint_count', 0)} Blueprint · "
                f"{result['passed_l1']} L1 pass "
                f"({result.get('incomplete', 0)} incomplete rejected)"
            ),
        )
        return json.dumps(
            {
                "status": "ok",
                "scan": meta,
                "high_count": result["high_conviction"],
                "watch_count": result["watchlist"],
                "low_count": result["low_conviction"],
                "incomplete_count": result.get("incomplete") or 0,
                "blueprint_count": result.get("blueprint_count") or 0,
                "passed_l1": result["passed_l1"],
                "total_raw": result["total_raw"],
                "top_review": top_review,
            }
        )
    except Exception as e:
        logger.exception("screener process failed")
        return json.dumps({"status": "error", "message": str(e)})
    finally:
        db.close()


_LIST_FIELDS = (
    "symbol",
    "name",
    "cmp",
    "score_total",
    "tier",
    "l1_passed",
    "layer3",
    "user_verified",
    "blueprint_tags",
    "blueprint_match",
    "blueprint_bonus",
)


def _slim_stock(row: Dict[str, Any]) -> Dict[str, Any]:
    return {k: row[k] for k in _LIST_FIELDS if k in row}


def _ensure_blueprint_on_stock(
    row: Dict[str, Any],
    bp_map: Dict[str, List[str]],
) -> Dict[str, Any]:
    """Re-tag from map when older scans lack persisted Blueprint fields."""
    tags = row.get("blueprint_tags")
    if not isinstance(tags, list) or not tags:
        tags = tags_for(str(row.get("symbol") or ""), bp_map)
    row["blueprint_tags"] = list(tags)
    row["blueprint_match"] = bool(tags)
    row["blueprint_bonus"] = 10.0 if tags else 0.0
    return row


def get_screener_dashboard_json(db_path: Optional[str] = None) -> str:
    db = Database(db_path)
    try:
        scan = db.latest_screener_scan()
        bp_map = _load_blueprint_map()
        stocks = []
        if scan:
            stocks = [
                _slim_stock(_ensure_blueprint_on_stock(s, bp_map))
                for s in db.get_screener_stocks()
            ]
        top_review = []
        counts = {"high": 0, "watch": 0, "low": 0, "all": 0, "blueprint": 0}
        if scan:
            top_review = scan.get("top_review") or []
            if isinstance(top_review, str):
                try:
                    top_review = json.loads(top_review)
                except Exception:
                    top_review = []
            blueprint_count = sum(1 for s in stocks if s.get("blueprint_match"))
            counts = {
                "high": scan.get("high_count") or 0,
                "watch": scan.get("watch_count") or 0,
                "low": scan.get("low_count") or 0,
                "all": scan.get("passed_l1") or len(stocks),
                "blueprint": blueprint_count,
            }
        return json.dumps(
            {"scan": scan, "stocks": stocks, "top_review": top_review, "counts": counts},
            default=str,
        )
    finally:
        db.close()


def get_screener_detail_json(symbol: str, db_path: Optional[str] = None) -> str:
    db = Database(db_path)
    try:
        stock = db.get_screener_stock(symbol.upper())
        if not stock:
            return json.dumps({"status": "error", "message": "Not found"})
        _ensure_blueprint_on_stock(stock, _load_blueprint_map())
        return json.dumps({"status": "ok", "stock": stock}, default=str)
    finally:
        db.close()


def set_screener_verified_json(symbol: str, verified: bool, db_path: Optional[str] = None) -> str:
    db = Database(db_path)
    try:
        db.set_screener_verified(symbol, verified)
        return json.dumps({"status": "ok"})
    finally:
        db.close()
