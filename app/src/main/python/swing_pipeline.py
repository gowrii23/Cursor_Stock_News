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


def _load_universe(db: Database) -> List[Dict[str, Any]]:
    universe = db.get_universe()
    if not universe:
        universe = load_json_asset("universe_nifty100.json", [])
    return universe


def _rows_from_db(db: Database, tickers: List[str]) -> Dict[str, List[Dict[str, Any]]]:
    out: Dict[str, List[Dict[str, Any]]] = {}
    for t in tickers:
        hist = db.get_price_history(t.upper(), limit=280)
        if len(hist) >= 60:
            out[t.upper()] = hist
    return out


def run_swing_screen(
    db_path: Optional[str] = None,
    progress_cb: Any = None,
) -> str:
    started = datetime.utcnow().isoformat()
    db = Database(db_path)
    try:
        report(progress_cb, 5, "Loading Nifty 100 universe…")
        universe = _load_universe(db)
        tickers = [u["ticker"].upper() for u in universe if u.get("ticker")]

        report(progress_cb, 10, f"Syncing NSE prices for {len(tickers)} symbols…")
        try:
            fetch_ohlc_nse(tickers, db, include_index=True, progress_cb=progress_cb)
        except Exception as e:
            logger.warning("Swing price sync: %s", e)

        report(progress_cb, 78, "Building price series…")
        prices = _rows_from_db(db, tickers)
        report(progress_cb, 82, f"Price data ready for {len(prices)}/{len(tickers)} symbols")

        index_rows = db.get_index_history(limit=280)
        regime = market_regime(index_rows)
        report(progress_cb, 85, f"Market regime: {regime['label']}")

        report(progress_cb, 88, "Running Momentum First screen…")
        momentum_hits = momentum_screen(universe, prices, bool(regime.get("bullish")))
        report(progress_cb, 93, f"Momentum: {len(momentum_hits)} hits")

        report(progress_cb, 95, "Running Sleeping Giant screen…")
        sleeping_hits = sleeping_screen(universe, prices)
        report(progress_cb, 98, f"Sleeping Giant: {len(sleeping_hits)} hits")

        all_hits = momentum_hits + sleeping_hits
        meta = {
            "run_at": started,
            "regime": regime.get("label"),
            "regime_bullish": 1 if regime.get("bullish") else 0,
            "momentum_count": len(momentum_hits),
            "sleeping_count": len(sleeping_hits),
            "universe_size": len(tickers),
            "priced_count": len(prices),
            "message": (
                f"regime={regime.get('label')} momentum={len(momentum_hits)} "
                f"sleeping={len(sleeping_hits)}"
            ),
        }
        db.save_swing_run(meta, all_hits)

        report(
            progress_cb,
            100,
            f"Done — {len(momentum_hits)} momentum · {len(sleeping_hits)} sleeping giant",
        )
        return json.dumps(
            {
                "status": "ok",
                "run": meta,
                "regime": regime,
                "momentum_count": len(momentum_hits),
                "sleeping_count": len(sleeping_hits),
            }
        )
    except Exception as e:
        logger.exception("swing screen failed")
        return json.dumps({"status": "error", "message": str(e)})
    finally:
        db.close()


def get_swing_dashboard_json(db_path: Optional[str] = None) -> str:
    db = Database(db_path)
    try:
        run = db.latest_swing_run()
        hits = db.get_swing_hits() if run else []
        regime = {
            "bullish": bool(run.get("regime_bullish")) if run else False,
            "label": run.get("regime") if run else "No run yet",
        }
        counts = {
            "momentum": run.get("momentum_count") or 0 if run else 0,
            "sleeping": run.get("sleeping_count") or 0 if run else 0,
            "all": len(hits),
        }
        slim = []
        for h in hits:
            slim.append(
                {
                    k: h[k]
                    for k in ("symbol", "name", "screen", "close", "score", "signals", "metrics")
                    if k in h
                }
            )
        return json.dumps({"run": run, "hits": slim, "regime": regime, "counts": counts}, default=str)
    finally:
        db.close()


def get_swing_detail_json(symbol: str, db_path: Optional[str] = None) -> str:
    db = Database(db_path)
    try:
        hit = db.get_swing_hit(symbol.upper())
        if not hit:
            return json.dumps({"status": "error", "message": "Not found"})
        return json.dumps({"status": "ok", "hit": hit})
    finally:
        db.close()
