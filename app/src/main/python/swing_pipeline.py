"""Swing trade screens — NSE bhavcopy only (free, no Kite)."""
from __future__ import annotations

import json
import logging
from datetime import datetime
from typing import Any, Dict, List, Optional

from db import Database
from nse_fetch import fetch_ohlc_nse
from pipeline import load_json_asset
from progress_report import report
from swing_indicators import market_regime
from swing_momentum import screen_universe as momentum_screen
from swing_sleeping_giant import screen_universe as sleeping_screen

logger = logging.getLogger(__name__)

TOP_N_DEFAULT = 8
PRICE_HISTORY_LIMIT = 400


def _load_universe(db: Database) -> List[Dict[str, Any]]:
    universe = db.get_universe()
    if not universe:
        universe = load_json_asset("universe_nifty100.json", [])
    return universe


def _rows_from_db(db: Database, tickers: List[str]) -> Dict[str, List[Dict[str, Any]]]:
    out: Dict[str, List[Dict[str, Any]]] = {}
    for t in tickers:
        hist = db.get_price_history(t.upper(), limit=PRICE_HISTORY_LIMIT)
        if len(hist) >= 60:
            out[t.upper()] = hist
    return out


def _price_as_of(prices: Dict[str, List[Dict[str, Any]]]) -> Optional[str]:
    dates = []
    for rows in prices.values():
        if rows and rows[-1].get("date"):
            dates.append(str(rows[-1]["date"]))
    return max(dates) if dates else None


def _dedupe_hits(momentum: List[Dict[str, Any]], sleeping: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """One row per symbol; keep higher score; note secondary screen."""
    best: Dict[str, Dict[str, Any]] = {}
    for hit in momentum + sleeping:
        sym = (hit.get("symbol") or "").upper()
        if not sym:
            continue
        existing = best.get(sym)
        if existing is None:
            best[sym] = dict(hit)
            best[sym]["also_screens"] = []
            continue
        also = existing.setdefault("also_screens", [])
        if hit.get("screen") and hit.get("screen") != existing.get("screen"):
            also.append(hit["screen"])
        if (hit.get("score") or 0) > (existing.get("score") or 0):
            prev_screen = existing.get("screen")
            merged_also = list(also)
            if prev_screen and prev_screen != hit.get("screen"):
                if prev_screen not in merged_also:
                    merged_also.append(prev_screen)
            best[sym] = dict(hit)
            best[sym]["also_screens"] = merged_also
    out = list(best.values())
    out.sort(key=lambda h: (h.get("score") or 0, h.get("symbol") or ""), reverse=True)
    return out


def run_swing_screen(
    db_path: Optional[str] = None,
    progress_cb: Any = None,
) -> str:
    started = datetime.utcnow().isoformat()
    db = Database(db_path)
    try:
        report(progress_cb, 3, "Loading Nifty 100 universe…")
        universe = _load_universe(db)
        tickers = [u["ticker"].upper() for u in universe if u.get("ticker")]
        report(progress_cb, 8, f"Universe: {len(tickers)} symbols — syncing NSE bhavcopy…")

        try:
            fetch_ohlc_nse(tickers, db, include_index=True, progress_cb=progress_cb)
        except Exception as e:
            logger.warning("Swing price sync: %s", e)
            report(progress_cb, 70, f"Price sync warning: {e} — using cache")

        report(progress_cb, 76, "Building price series from cache…")
        prices = _rows_from_db(db, tickers)
        as_of = _price_as_of(prices)
        coverage = f"{len(prices)}/{len(tickers)}"
        report(progress_cb, 80, f"Price data ready: {coverage} symbols" + (f" as of {as_of}" if as_of else ""))

        report(progress_cb, 83, "Checking market regime (Nifty vs 200 SMA)…")
        index_rows = db.get_index_history(limit=PRICE_HISTORY_LIMIT)
        regime = market_regime(index_rows)
        report(progress_cb, 86, f"Regime: {regime.get('label')}")

        momentum_hits: List[Dict[str, Any]] = []
        if regime.get("state") == "bullish":
            report(progress_cb, 88, "Running Momentum First…")
            momentum_hits = momentum_screen(universe, prices, True)
            report(progress_cb, 93, f"Momentum: {len(momentum_hits)} hits")
        elif regime.get("state") == "insufficient":
            report(progress_cb, 93, "Momentum skipped — insufficient index history")
        else:
            report(progress_cb, 93, "Momentum skipped — bearish regime")

        report(progress_cb, 94, "Running Sleeping Giant…")
        sleeping_hits = sleeping_screen(universe, prices)
        report(progress_cb, 97, f"Sleeping Giant: {len(sleeping_hits)} hits")

        all_hits = _dedupe_hits(momentum_hits, sleeping_hits)
        meta = {
            "run_at": started,
            "as_of": as_of or regime.get("as_of"),
            "regime": regime.get("label"),
            "regime_state": regime.get("state"),
            "regime_bullish": 1 if regime.get("bullish") else 0,
            "momentum_count": len(momentum_hits),
            "sleeping_count": len(sleeping_hits),
            "universe_size": len(tickers),
            "priced_count": len(prices),
            "hit_count": len(all_hits),
            "message": (
                f"as_of={as_of} priced={coverage} regime={regime.get('state')} "
                f"momentum={len(momentum_hits)} sleeping={len(sleeping_hits)}"
            ),
        }
        db.save_swing_run(meta, all_hits)

        report(
            progress_cb,
            100,
            f"Done — top list {min(TOP_N_DEFAULT, len(all_hits))} of {len(all_hits)} "
            f"({len(momentum_hits)} mom · {len(sleeping_hits)} sleep)",
        )
        return json.dumps(
            {
                "status": "ok",
                "run": meta,
                "regime": regime,
                "momentum_count": len(momentum_hits),
                "sleeping_count": len(sleeping_hits),
                "hit_count": len(all_hits),
                "as_of": as_of,
                "priced_count": len(prices),
                "universe_size": len(tickers),
            }
        )
    except Exception as e:
        logger.exception("swing screen failed")
        return json.dumps({"status": "error", "message": str(e)})
    finally:
        db.close()


def get_swing_dashboard_json(db_path: Optional[str] = None, top_n: int = TOP_N_DEFAULT) -> str:
    db = Database(db_path)
    try:
        run = db.latest_swing_run()
        hits_all = db.get_swing_hits() if run else []
        # Prefer stored counts; recompute screen filters from hits for chip accuracy after dedupe
        mom = [h for h in hits_all if h.get("screen") == "momentum"]
        sleep = [h for h in hits_all if h.get("screen") == "sleeping"]
        regime_state = (run.get("regime_state") if run else None) or (
            "bullish" if run and run.get("regime_bullish") else ("bearish" if run else "insufficient")
        )
        if not run:
            regime_state = "insufficient"
        regime = {
            "state": regime_state,
            "bullish": regime_state == "bullish",
            "label": (run.get("regime") if run else None) or "No run yet — tap Run Swing Screen",
            "as_of": run.get("as_of") if run else None,
        }
        counts = {
            "momentum": len(mom),
            "sleeping": len(sleep),
            "all": len(hits_all),
        }
        # Return all hits ranked; UI shows top-N per filter
        slim = []
        for h in hits_all:
            row = {
                k: h.get(k)
                for k in (
                    "symbol",
                    "name",
                    "screen",
                    "close",
                    "score",
                    "signals",
                    "metrics",
                    "also_screens",
                    "as_of",
                )
                if k in h or h.get(k) is not None
            }
            # Ensure required keys exist as safe defaults
            row.setdefault("symbol", "")
            row.setdefault("signals", h.get("signals") or [])
            row.setdefault("metrics", h.get("metrics") or {})
            slim.append(row)

        coverage = {
            "priced_count": (run.get("priced_count") if run else 0) or 0,
            "universe_size": (run.get("universe_size") if run else 0) or 0,
            "as_of": run.get("as_of") if run else None,
            "top_n": TOP_N_DEFAULT,
            "total_hits": len(hits_all),
        }
        return json.dumps(
            {
                "run": run,
                "hits": slim,
                "regime": regime,
                "counts": counts,
                "coverage": coverage,
            },
            default=str,
        )
    finally:
        db.close()


def get_swing_detail_json(symbol: str, db_path: Optional[str] = None) -> str:
    db = Database(db_path)
    try:
        hit = db.get_swing_hit(symbol.upper())
        if not hit:
            return json.dumps({"status": "error", "message": "Not found"})
        return json.dumps({"status": "ok", "hit": hit}, default=str)
    finally:
        db.close()
