"""End-to-end daily screener pipeline — callable from Kotlin via Chaquopy."""
from __future__ import annotations

import json
import logging
import os
import traceback
from datetime import datetime
from typing import Any, Dict, List, Optional

from beta_alpha import percentile_rank, residual_std, rolling_windows
from blueprint_tagger import load_blueprint_map, tags_for
from conviction_score import conviction_score
from data_fetch import align_returns, fetch_ohlc, generate_demo_prices
from db import Database
from drop_detector import is_idiosyncratic_drop
from news_fetch import (
    demo_headlines,
    fetch_nse_announcements,
    fetch_pulse_headlines,
    match_headlines_to_universe,
)
from severity_filter import classify_best, classify_severity

logger = logging.getLogger(__name__)
logging.basicConfig(level=logging.INFO)


def _asset_path(*parts: str) -> str:
    """Resolve bundled asset path (copied next to python or via Android assets mirror)."""
    here = os.path.dirname(__file__)
    candidates = [
        os.path.join(here, *parts),
        os.path.join(here, "..", "assets", *parts),
        os.path.join(os.environ.get("HOME", ""), "assets", *parts),
    ]
    for c in candidates:
        if os.path.exists(c):
            return c
    return candidates[0]


def load_json_asset(filename: str, fallback: Any) -> Any:
    path = _asset_path(filename)
    # Also try Android-extracted files dir
    alt = os.path.join(os.environ.get("HOME", ""), filename)
    for p in (path, alt):
        if os.path.exists(p):
            with open(p, "r", encoding="utf-8") as f:
                return json.load(f)
    return fallback


def init_db(db_path: Optional[str] = None) -> Database:
    db = Database(db_path)
    universe = load_json_asset("universe_nifty100.json", [])
    if universe:
        db.upsert_universe(universe)
    defaults = load_json_asset("settings_defaults.json", {})
    if defaults and db.get_setting("initialized") is None:
        for k, v in defaults.items():
            db.set_setting(k, v)
        db.set_setting("initialized", True)
    return db


def run_daily_screen(
    db_path: Optional[str] = None,
    use_live: bool = True,
    force_demo: bool = False,
) -> str:
    """
    Run the full EOD pipeline. Returns JSON string for Kotlin.
    """
    started = datetime.utcnow().isoformat()
    db = init_db(db_path)
    try:
        universe = db.get_universe()
        if not universe:
            universe = load_json_asset("universe_nifty100.json", [])
            db.upsert_universe(universe)

        tickers = [u["ticker"] for u in universe]
        z_threshold = float(db.get_setting("z_threshold", -1.5))
        exclude_kw = db.get_setting("exclude_keywords", None)
        candidate_kw = db.get_setting("candidate_keywords", None)

        blueprint_path = _asset_path("blueprint_tags.json")
        alt_bp = os.path.join(os.environ.get("HOME", ""), "blueprint_tags.json")
        bp_map = load_blueprint_map(
            blueprint_path if os.path.exists(blueprint_path) else alt_bp
        )
        if not bp_map:
            bp_map = load_json_asset("blueprint_tags.json", {})

        prices: Dict[str, Any] = {}
        mode = "demo"
        if use_live and not force_demo:
            try:
                prices = fetch_ohlc(tickers, period="3y", include_index=True)
                if "NIFTY50" in prices and len(prices) > 20:
                    mode = "live"
                else:
                    logger.warning("Live fetch incomplete (%d series); using demo", len(prices))
                    prices = {}
            except Exception as e:
                logger.warning("Live fetch error: %s", e)
                prices = {}

        if not prices:
            prices = generate_demo_prices(tickers)
            mode = "demo"

        index_df = prices.get("NIFTY50")
        if index_df is None or index_df.empty:
            raise RuntimeError("Missing index prices")

        # Compute per-ticker metrics
        metrics_rows = []
        alpha_1y_list = []
        stock_stats = {}

        for t in tickers:
            df = prices.get(t)
            if df is None or df.empty:
                continue
            s_ret, i_ret = align_returns(df["Close"], index_df["Close"])
            if len(s_ret) < 60:
                continue
            beta_1y, alpha_1y, alpha_3y = rolling_windows(s_ret, i_ret)
            idio_std = residual_std(s_ret.iloc[:-1], i_ret.iloc[:-1], beta_1y)
            stock_ret_today = float(s_ret.iloc[-1])
            index_ret_today = float(i_ret.iloc[-1])
            asof = s_ret.index[-1].strftime("%Y-%m-%d")
            close = float(df["Close"].iloc[-1])
            metrics_rows.append(
                {
                    "ticker": t,
                    "date": asof,
                    "close": close,
                    "daily_return": stock_ret_today,
                    "beta_1y": None if _nan(beta_1y) else float(beta_1y),
                    "alpha_1y": None if _nan(alpha_1y) else float(alpha_1y),
                    "alpha_3y": None if _nan(alpha_3y) else float(alpha_3y),
                }
            )
            if not _nan(alpha_1y):
                alpha_1y_list.append(float(alpha_1y))
            stock_stats[t] = {
                "beta_1y": beta_1y,
                "alpha_1y": alpha_1y,
                "alpha_3y": alpha_3y,
                "idio_std": idio_std,
                "stock_ret_today": stock_ret_today,
                "index_ret_today": index_ret_today,
                "asof": asof,
                "close": close,
            }

        db.upsert_daily_metrics(metrics_rows)

        # Flag idiosyncratic drops
        flagged = []
        for t, st in stock_stats.items():
            ok, idio, z = is_idiosyncratic_drop(
                st["stock_ret_today"],
                st["index_ret_today"],
                st["beta_1y"],
                st["idio_std"],
                z_threshold=z_threshold,
            )
            if ok:
                flagged.append((t, idio, z))

        # News
        news_items: List[Dict[str, Any]] = []
        if mode == "live":
            pulse = fetch_pulse_headlines()
            nse = fetch_nse_announcements()
            news_items = match_headlines_to_universe(pulse + nse, universe)
        if not news_items:
            news_items = demo_headlines([t for t, _, _ in flagged], universe)

        # Classify + score
        news_by_ticker: Dict[str, List[Dict[str, Any]]] = {}
        for n in news_items:
            n["severity_tag"] = classify_severity(
                n.get("headline", ""), exclude_kw, candidate_kw
            )
            news_by_ticker.setdefault(n.get("ticker") or "", []).append(n)

        watch_rows = []
        asof_date = None
        for t, idio, z in flagged:
            st = stock_stats[t]
            asof_date = st["asof"]
            related = news_by_ticker.get(t, [])
            headlines = [r.get("headline", "") for r in related]
            severity = classify_best(headlines, exclude_kw, candidate_kw) if headlines else "UNKNOWN"
            headline = headlines[0] if headlines else "No matching headline found"
            source = related[0].get("source", "") if related else ""
            bp = tags_for(t, bp_map)
            alpha_pct = percentile_rank(alpha_1y_list, st["alpha_1y"])
            score = conviction_score(
                z_drop=z,
                alpha_percentile=alpha_pct,
                beta=st["beta_1y"],
                severity_tag=severity,
                blueprint_match=bool(bp),
            )
            watch_rows.append(
                {
                    "ticker": t,
                    "z_score": z,
                    "idiosyncratic_return": idio,
                    "headline": headline,
                    "source": source,
                    "severity_tag": severity,
                    "blueprint_tags": bp,
                    "conviction_score": score,
                    "beta_1y": None if _nan(st["beta_1y"]) else float(st["beta_1y"]),
                    "alpha_1y": None if _nan(st["alpha_1y"]) else float(st["alpha_1y"]),
                    "alpha_percentile": alpha_pct,
                }
            )

        if asof_date is None and metrics_rows:
            asof_date = metrics_rows[0]["date"]
        if asof_date is None:
            asof_date = datetime.utcnow().date().isoformat()

        # Sort by conviction
        watch_rows.sort(key=lambda r: r.get("conviction_score") or -999, reverse=True)
        db.replace_watchlist_for_date(asof_date, watch_rows)

        # Persist news (matched + severity)
        flat_news = []
        for t, rows in news_by_ticker.items():
            for r in rows:
                flat_news.append(
                    {
                        "ticker": t,
                        "date": r.get("date") or asof_date,
                        "headline": r.get("headline"),
                        "source": r.get("source"),
                        "url": r.get("url"),
                        "severity_tag": r.get("severity_tag"),
                    }
                )
        if flat_news:
            db.insert_news(flat_news)

        finished = datetime.utcnow().isoformat()
        msg = f"mode={mode} universe={len(tickers)} flagged={len(watch_rows)}"
        db.log_run(started, finished, "ok", msg, len(watch_rows))

        result = {
            "status": "ok",
            "mode": mode,
            "date": asof_date,
            "flagged_count": len(watch_rows),
            "message": msg,
            "watchlist": watch_rows,
        }
        return json.dumps(result)
    except Exception as e:
        finished = datetime.utcnow().isoformat()
        err = f"{e}\n{traceback.format_exc()}"
        logger.error(err)
        try:
            db.log_run(started, finished, "error", str(e), 0)
        except Exception:
            pass
        return json.dumps({"status": "error", "message": str(e)})
    finally:
        db.close()


def get_dashboard_json(db_path: Optional[str] = None) -> str:
    db = init_db(db_path)
    try:
        watch = db.get_watchlist()
        run = db.latest_run()
        return json.dumps({"watchlist": watch, "latest_run": run})
    finally:
        db.close()


def get_stock_detail_json(ticker: str, db_path: Optional[str] = None) -> str:
    db = init_db(db_path)
    try:
        metrics = db.get_stock_metrics(ticker)
        history = db.get_stock_watch_history(ticker)
        news = db.get_news(ticker=ticker, limit=40)
        universe = {u["ticker"]: u for u in db.get_universe()}
        bp = load_json_asset("blueprint_tags.json", {})
        return json.dumps(
            {
                "ticker": ticker,
                "meta": universe.get(ticker, {"ticker": ticker}),
                "blueprint_tags": bp.get(ticker.upper(), bp.get(ticker, [])),
                "metrics": metrics,
                "watch_history": history,
                "news": news,
            }
        )
    finally:
        db.close()


def get_news_json(ticker: Optional[str] = None, db_path: Optional[str] = None) -> str:
    db = init_db(db_path)
    try:
        return json.dumps({"news": db.get_news(ticker=ticker, limit=150)})
    finally:
        db.close()


def get_settings_json(db_path: Optional[str] = None) -> str:
    db = init_db(db_path)
    try:
        keys = [
            "z_threshold",
            "beta_low_threshold",
            "job_hour_ist",
            "require_wifi",
            "require_charging",
            "exclude_keywords",
            "candidate_keywords",
        ]
        out = {k: db.get_setting(k) for k in keys}
        out["blueprint_tags"] = load_json_asset("blueprint_tags.json", {})
        return json.dumps(out)
    finally:
        db.close()


def save_settings_json(payload: str, db_path: Optional[str] = None) -> str:
    db = init_db(db_path)
    try:
        data = json.loads(payload)
        for k, v in data.items():
            if k == "blueprint_tags":
                # Persist override under HOME
                path = os.path.join(os.environ.get("HOME", ""), "blueprint_tags.json")
                with open(path, "w", encoding="utf-8") as f:
                    json.dump(v, f, indent=2)
            else:
                db.set_setting(k, v)
        return json.dumps({"status": "ok"})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})
    finally:
        db.close()


def _nan(x: Any) -> bool:
    try:
        import math

        return x is None or (isinstance(x, float) and math.isnan(x))
    except Exception:
        return x is None


if __name__ == "__main__":
    print(run_daily_screen(force_demo=True))
