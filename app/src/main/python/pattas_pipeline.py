"""Pattas capture → score → persist orchestration."""
from __future__ import annotations

import json
import logging
from datetime import datetime
from typing import Any, Dict, List, Optional

from db import Database
from nse_fetch import fetch_ohlc_nse
from pattas_engine import score_rows
from progress_report import report

logger = logging.getLogger(__name__)

_MERGE_FIELDS = ("ind_pe", "debt_eq", "roe_3y", "div_yield", "pe", "cmp", "name")


def _merge_screener_fields(row: Dict[str, Any], db: Database) -> Dict[str, Any]:
    """Fill gaps from the latest Screener-tab capture when company pages omit them."""
    sym = (row.get("symbol") or "").upper()
    if not sym:
        return row
    screener = db.get_screener_stock(sym)
    if not screener:
        return row
    raw = screener.get("raw_columns") or {}
    merged = dict(row)
    alias_map = {
        "pe": "P/E",
        "div_yield": "Div Yld %",
        "debt_eq": "Debt / Eq",
        "roe_3y": "ROE 3Yr %",
        "ind_pe": "Ind PE",
        "cmp": "CMP Rs.",
        "name": "name",
    }
    for field, raw_key in alias_map.items():
        if field == "name":
            if not merged.get("name") and screener.get("name"):
                merged["name"] = screener["name"]
            continue
        existing = merged.get(raw_key)
        if existing not in (None, "", "-", "—"):
            continue
        val = screener.get(field)
        if val is None and isinstance(raw, dict):
            for k, v in raw.items():
                if k.lower().replace(" ", "") in raw_key.lower().replace(" ", ""):
                    val = v
                    break
        if val is not None:
            merged[raw_key] = val
    return merged


def _enrich_rows(rows: List[Dict[str, Any]], db: Database) -> List[Dict[str, Any]]:
    return [_merge_screener_fields(r, db) for r in rows]


def start_pattas_scan(
    db_path: Optional[str] = None,
    progress_cb: Any = None,
) -> str:
    """Return symbol list for WebView-only Pattas capture (no headless HTTP)."""
    db = Database(db_path)
    try:
        db.seed_pattas_symbols_if_empty()
        symbols = db.get_pattas_symbols()
        sym_list = [s["symbol"] for s in symbols if s.get("symbol")]
        if not sym_list:
            return json.dumps({"status": "error", "message": "Pattas symbol list is empty"})
        report(progress_cb, 5, f"Ready — {len(sym_list)} symbols for WebView capture")
        return json.dumps(
            {
                "status": "needs_webview",
                "symbols": sym_list,
                "captured_rows": [],
                "failed_symbols": sym_list,
            }
        )
    except Exception as e:
        logger.exception("pattas scan start failed")
        return json.dumps({"status": "error", "message": str(e)})
    finally:
        db.close()


def finish_pattas_scan_with_webview_rows(
    captured_rows_json: str,
    webview_rows_json: str,
    db_path: Optional[str] = None,
    progress_cb: Any = None,
) -> str:
    db = Database(db_path)
    try:
        captured = json.loads(captured_rows_json) if captured_rows_json else []
        webview = json.loads(webview_rows_json) if webview_rows_json else []
        if not isinstance(captured, list) or not isinstance(webview, list):
            return json.dumps({"status": "error", "message": "Invalid rows payload"})
        return _finalize_pattas_scan(captured + webview, db, progress_cb)
    except Exception as e:
        logger.exception("pattas webview finish failed")
        return json.dumps({"status": "error", "message": str(e)})
    finally:
        db.close()


def _finalize_pattas_scan(
    rows: List[Dict[str, Any]],
    db: Database,
    progress_cb: Any,
) -> str:
    started = datetime.utcnow().isoformat()
    enriched = _enrich_rows(rows, db)
    report(progress_cb, 55, "Scoring vs peer medians…")
    scored = score_rows(enriched)
    syms = [r["symbol"] for r in scored if r.get("symbol")]
    if syms:
        report(progress_cb, 65, f"Fetching NSE prices for {len(syms)} symbols…")
        try:
            fetch_ohlc_nse(syms, db, include_index=False, progress_cb=progress_cb)
        except Exception as e:
            logger.warning("Pattas price fetch: %s", e)

    meta = {
        "scanned_at": started,
        "symbol_count": len(scored),
        "message": f"pattas={len(scored)}",
    }
    db.save_pattas_scan(meta, scored)
    report(progress_cb, 100, f"Done — {len(scored)} Pattas stocks scored")
    return json.dumps(
        {
            "status": "ok",
            "scan": meta,
            "count": len(scored),
        }
    )


_LIST_FIELDS = (
    "symbol",
    "name",
    "cmp",
    "pe",
    "div_yield",
    "debt_eq",
    "roe_3y",
    "ind_pe",
    "pattas",
    "user_moat_verified",
)


def _slim_stock(row: Dict[str, Any]) -> Dict[str, Any]:
    out = {k: row.get(k) for k in _LIST_FIELDS if k in row}
    pattas = row.get("pattas") or {}
    out["pattas_score"] = pattas.get("pattas_score", 0)
    out["peer_group_size"] = pattas.get("peer_group_size", 0)
    out["used_basket_fallback"] = pattas.get("used_basket_fallback", False)
    out["pillars"] = pattas.get("pillars") or {}
    out["peer_medians"] = pattas.get("peer_medians") or {}
    return out


def get_pattas_dashboard_json(db_path: Optional[str] = None) -> str:
    db = Database(db_path)
    try:
        db.seed_pattas_symbols_if_empty()
        scan = db.latest_pattas_scan()
        stocks = [_slim_stock(s) for s in db.get_pattas_stocks()] if scan else []
        candidates = db.get_pattas_candidates()
        symbol_count = len(db.get_pattas_symbols())
        return json.dumps(
            {
                "scan": scan,
                "stocks": stocks,
                "candidates": candidates,
                "symbol_count": symbol_count,
            },
            default=str,
        )
    finally:
        db.close()


def get_pattas_detail_json(symbol: str, db_path: Optional[str] = None) -> str:
    db = Database(db_path)
    try:
        stock = db.get_pattas_stock(symbol.upper())
        if not stock:
            return json.dumps({"status": "error", "message": "Not found"})
        return json.dumps({"status": "ok", "stock": stock}, default=str)
    finally:
        db.close()


def set_pattas_moat_verified_json(
    symbol: str,
    verified: bool,
    db_path: Optional[str] = None,
) -> str:
    db = Database(db_path)
    try:
        db.set_pattas_moat_verified(symbol, verified)
        return json.dumps({"status": "ok"})
    finally:
        db.close()


def get_pattas_symbols_json(db_path: Optional[str] = None) -> str:
    db = Database(db_path)
    try:
        db.seed_pattas_symbols_if_empty()
        return json.dumps({"symbols": db.get_pattas_symbols()}, default=str)
    finally:
        db.close()


def add_pattas_symbol_json(
    symbol: str,
    name: Optional[str] = None,
    note: Optional[str] = None,
    db_path: Optional[str] = None,
) -> str:
    db = Database(db_path)
    try:
        db.add_pattas_symbol(symbol, name=name, note=note)
        return json.dumps({"status": "ok"})
    finally:
        db.close()


def remove_pattas_symbol_json(symbol: str, db_path: Optional[str] = None) -> str:
    db = Database(db_path)
    try:
        db.remove_pattas_symbol(symbol)
        return json.dumps({"status": "ok"})
    finally:
        db.close()


def get_pattas_candidates_json(db_path: Optional[str] = None) -> str:
    db = Database(db_path)
    try:
        return json.dumps({"candidates": db.get_pattas_candidates()}, default=str)
    finally:
        db.close()
