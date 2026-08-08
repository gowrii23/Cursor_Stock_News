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
from conviction_score import conviction_score, conviction_score_breakdown
from data_fetch import align_returns
from db import Database
from drop_detector import is_idiosyncratic_drop
from nse_fetch import fetch_ohlc_nse
from news_fetch import (
    DEMO_TICKER,
    demo_headlines,
    fetch_google_news_for_tickers,
    fetch_nse_announcements,
    fetch_pulse_headlines,
    match_headlines_to_universe,
    pulse_supplement,
)
from severity_filter import classify_best, classify_severity
from market_time import timing_vs_market_close
from progress_report import report

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


def _run_demo_screen(
    db: Database,
    started: str,
    exclude_kw: Any,
    candidate_kw: Any,
) -> str:
    """Offline demo: one [TEST] watchlist row + synthetic headline (not live news)."""
    asof_date = datetime.utcnow().date().isoformat()
    news_items = demo_headlines()
    for n in news_items:
        n["severity_tag"] = classify_severity(
            n.get("headline", ""), exclude_kw, candidate_kw
        )

    demo = news_items[0]
    severity = demo["severity_tag"]
    z = -2.35
    breakdown = conviction_score_breakdown(
        z_drop=z,
        alpha_percentile=0.55,
        beta=0.95,
        severity_tag=severity,
        blueprint_match=False,
    )
    score = breakdown["total"]
    watch_rows = [
        {
            "ticker": DEMO_TICKER,
            "z_score": z,
            "idiosyncratic_return": -0.028,
            "daily_return": -0.031,
            "headline": demo["headline"],
            "source": "demo",
            "severity_tag": severity,
            "blueprint_tags": [],
            "conviction_score": score,
            "score_breakdown": breakdown,
            "beta_1y": 0.95,
            "alpha_1y": 0.08,
            "alpha_percentile": 0.55,
        }
    ]

    db.replace_watchlist_for_date(asof_date, watch_rows)
    db.insert_news(
        [
            {
                "ticker": DEMO_TICKER,
                "date": asof_date,
                "headline": demo["headline"],
                "source": "demo",
                "url": "",
                "severity_tag": severity,
            }
        ]
    )

    finished = datetime.utcnow().isoformat()
    msg = f"mode=demo universe=1 flagged=1 mock={DEMO_TICKER}"
    db.log_run(started, finished, "ok", msg, 1)
    return json.dumps(
        {
            "status": "ok",
            "mode": "demo",
            "date": asof_date,
            "flagged_count": 1,
            "message": msg,
            "watchlist": watch_rows,
        }
    )


def run_daily_screen(
    db_path: Optional[str] = None,
    use_live: bool = True,
    force_demo: bool = False,
    progress_cb: Any = None,
) -> str:
    """
    Run the full EOD pipeline. Returns JSON string for Kotlin.
    """
    started = datetime.utcnow().isoformat()
    report(progress_cb, 1, "Initializing database…")
    db = init_db(db_path)
    try:
        universe = db.get_universe()
        if not universe:
            universe = load_json_asset("universe_nifty100.json", [])
            db.upsert_universe(universe)

        tickers = [u["ticker"] for u in universe]
        report(progress_cb, 3, f"Universe loaded: {len(tickers)} tickers")
        z_threshold = float(db.get_setting("z_threshold", -1.5))
        min_idio = float(db.get_setting("min_idio_return", -0.015))
        exclude_kw = db.get_setting("exclude_keywords", None)
        candidate_kw = db.get_setting("candidate_keywords", None)

        blueprint_path = _asset_path("blueprint_tags.json")
        alt_bp = os.path.join(os.environ.get("HOME", ""), "blueprint_tags.json")
        bp_map = load_blueprint_map(
            blueprint_path if os.path.exists(blueprint_path) else alt_bp
        )
        if not bp_map:
            bp_map = load_json_asset("blueprint_tags.json", {})

        if force_demo or not use_live:
            report(progress_cb, 100, "Demo mode complete")
            return _run_demo_screen(db, started, exclude_kw, candidate_kw)

        prices: Dict[str, Any] = {}
        mode = "live"
        price_health: Dict[str, str] = {"bhavcopy": "fail"}
        try:
            prices, price_health = fetch_ohlc_nse(
                tickers, db, include_index=True, progress_cb=progress_cb
            )
            have_tickers = len([t for t in tickers if t in prices])
            if "NIFTY50" not in prices or have_tickers < max(15, len(tickers) // 5):
                cached = db.get_watchlist()
                if cached:
                    report(progress_cb, 100, f"Using cached watchlist ({len(cached)} flags)")
                    finished = datetime.utcnow().isoformat()
                    msg = (
                        f"mode=cached universe={len(tickers)} flagged={len(cached)} "
                        f"bhavcopy={price_health.get('bhavcopy', 'fail')} "
                        f"reason=insufficient_prices"
                    )
                    db.purge_demo_watchlist()
                    db.log_run(started, finished, "partial", msg, len(cached))
                    return json.dumps(
                        {
                            "status": "partial",
                            "mode": "cached",
                            "date": cached[0].get("date"),
                            "flagged_count": len(cached),
                            "message": msg,
                            "data_health": _build_health(
                                mode="cached",
                                bhavcopy=price_health.get("bhavcopy", "fail"),
                            ),
                            "watchlist": cached,
                        }
                    )
                logger.warning(
                    "NSE prices insufficient (%d/%d); no cache — demo seed",
                    have_tickers,
                    len(tickers),
                )
                return _run_demo_screen(db, started, exclude_kw, candidate_kw)
        except Exception as e:
            logger.warning("NSE price fetch error: %s", e)
            cached = db.get_watchlist()
            if cached:
                finished = datetime.utcnow().isoformat()
                msg = f"mode=cached universe={len(tickers)} flagged={len(cached)} bhavcopy=fail"
                db.purge_demo_watchlist()
                db.log_run(started, finished, "partial", msg, len(cached))
                return json.dumps(
                    {
                        "status": "partial",
                        "mode": "cached",
                        "date": cached[0].get("date"),
                        "flagged_count": len(cached),
                        "message": msg,
                        "data_health": _build_health(mode="cached", bhavcopy="fail"),
                        "watchlist": cached,
                    }
                )
            return _run_demo_screen(db, started, exclude_kw, candidate_kw)

        index_df = prices.get("NIFTY50")
        if index_df is None or index_df.empty:
            raise RuntimeError("Missing index prices")

        report(progress_cb, 76, "Computing beta / alpha / z-scores…")
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
        report(progress_cb, 82, f"Metrics saved for {len(metrics_rows)} tickers")

        # Flag idiosyncratic drops (z-score + minimum idiosyncratic move)
        flagged = []
        for t, st in stock_stats.items():
            ok, idio, z = is_idiosyncratic_drop(
                st["stock_ret_today"],
                st["index_ret_today"],
                st["beta_1y"],
                st["idio_std"],
                z_threshold=z_threshold,
            )
            if ok and idio <= min_idio:
                flagged.append((t, idio, z))

        report(progress_cb, 85, f"Flagged {len(flagged)} idiosyncratic drops")

        # News — bulk feeds + Google News for flagged tickers only
        news_items: List[Dict[str, Any]] = []
        report(progress_cb, 86, "Fetching Zerodha Pulse headlines…")
        pulse_raw = fetch_pulse_headlines()
        pulse_ok = len(pulse_raw) > 0
        report(progress_cb, 89, f"Pulse: {len(pulse_raw)} headlines")
        report(progress_cb, 90, "Fetching NSE corporate announcements…")
        nse_raw = fetch_nse_announcements()
        nse_ok = len(nse_raw) > 0
        report(progress_cb, 93, f"NSE announcements: {len(nse_raw)} rows")
        flagged_tickers = [t for t, _, _ in flagged]
        gnews_raw: List[Dict[str, Any]] = []
        gnews_ok = False
        if flagged_tickers:
            report(
                progress_cb,
                94,
                f"Fetching Google News for {len(flagged_tickers)} flagged tickers…",
            )
            gnews_raw = fetch_google_news_for_tickers(universe, flagged_tickers)
            gnews_ok = len(gnews_raw) > 0
            report(progress_cb, 96, f"Google News: {len(gnews_raw)} headlines")
        else:
            report(progress_cb, 96, "Google News: skipped (no flags)")
        news_items = match_headlines_to_universe(pulse_raw + nse_raw, universe)
        # Google News already has ticker attached
        news_items.extend(gnews_raw)

        report(progress_cb, 97, "Classifying severity and scoring…")
        # Classify + score
        news_by_ticker: Dict[str, List[Dict[str, Any]]] = {}
        for n in news_items:
            n["severity_tag"] = classify_severity(
                n.get("headline", ""), exclude_kw, candidate_kw
            )
            n["timing_vs_close"] = timing_vs_market_close(
                n.get("published_at"), n.get("date")
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
            breakdown = conviction_score_breakdown(
                z_drop=z,
                alpha_percentile=alpha_pct,
                beta=st["beta_1y"],
                severity_tag=severity,
                blueprint_match=bool(bp),
            )
            score = breakdown["total"]
            watch_rows.append(
                {
                    "ticker": t,
                    "z_score": z,
                    "idiosyncratic_return": idio,
                    "daily_return": st["stock_ret_today"],
                    "headline": headline,
                    "source": source,
                    "severity_tag": severity,
                    "blueprint_tags": bp,
                    "conviction_score": score,
                    "score_breakdown": breakdown,
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
        if watch_rows:
            db.purge_demo_watchlist()

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
                        "published_at": r.get("published_at"),
                        "timing_vs_close": r.get("timing_vs_close"),
                    }
                )
        if flat_news:
            db.insert_news(flat_news)

        report(progress_cb, 99, f"Watchlist ready: {len(watch_rows)} flags")
        finished = datetime.utcnow().isoformat()
        bhav_status = price_health.get("bhavcopy", "ok")
        msg = (
            f"mode={mode} universe={len(tickers)} flagged={len(watch_rows)} "
            f"bhavcopy={bhav_status} pulse={'ok' if pulse_ok else 'fail'} "
            f"nse={'ok' if nse_ok else 'fail'} gnews={'ok' if gnews_ok else 'skip'}"
        )
        db.log_run(started, finished, "ok", msg, len(watch_rows))

        result = {
            "status": "ok",
            "mode": mode,
            "date": asof_date,
            "flagged_count": len(watch_rows),
            "message": msg,
            "data_health": _build_health(
                mode=mode,
                bhavcopy=bhav_status,
                pulse="ok" if pulse_ok else "fail",
                nse="ok" if nse_ok else "fail",
                gnews="ok" if gnews_ok else "skip",
            ),
            "watchlist": watch_rows,
        }
        report(progress_cb, 100, f"Done — {len(watch_rows)} flags ({mode} mode)")
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


def _build_health(
    mode: str = "live",
    bhavcopy: str = "unknown",
    pulse: str = "unknown",
    nse: str = "unknown",
    gnews: str = "unknown",
) -> Dict[str, str]:
    return {
        "mode": mode,
        "bhavcopy": bhavcopy,
        "pulse": pulse,
        "nse": nse,
        "gnews": gnews,
    }


def _parse_run_health(run: Optional[Dict[str, Any]]) -> Dict[str, Any]:
    if not run:
        return _build_health(mode="unknown")
    msg = run.get("message") or ""
    health = _build_health(mode="demo")
    for token in msg.split():
        if token.startswith("mode="):
            health["mode"] = token.split("=", 1)[1]
        elif token.startswith("bhavcopy="):
            health["bhavcopy"] = token.split("=", 1)[1]
        elif token.startswith("yahoo="):
            # Legacy runs before Tier A
            health["bhavcopy"] = token.split("=", 1)[1]
        elif token.startswith("pulse="):
            health["pulse"] = token.split("=", 1)[1]
        elif token.startswith("nse="):
            health["nse"] = token.split("=", 1)[1]
        elif token.startswith("gnews="):
            health["gnews"] = token.split("=", 1)[1]
    return health


def get_dashboard_json(db_path: Optional[str] = None) -> str:
    db = init_db(db_path)
    try:
        watch = db.get_watchlist()
        run = db.latest_run()
        health = _parse_run_health(run)
        return json.dumps({"watchlist": watch, "latest_run": run, "data_health": health})
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
        latest_breakdown = history[0].get("score_breakdown") if history else None
        return json.dumps(
            {
                "ticker": ticker,
                "meta": universe.get(ticker, {"ticker": ticker}),
                "blueprint_tags": bp.get(ticker.upper(), bp.get(ticker, [])),
                "metrics": metrics,
                "watch_history": history,
                "news": news,
                "score_breakdown": latest_breakdown,
            }
        )
    finally:
        db.close()


def get_news_json(ticker: Optional[str] = None, db_path: Optional[str] = None) -> str:
    db = init_db(db_path)
    try:
        primary = db.get_news(ticker=ticker, limit=150)
        pulse_feed: List[Dict[str, Any]] = []
        if ticker is None:
            exclude_kw = db.get_setting("exclude_keywords", None)
            candidate_kw = db.get_setting("candidate_keywords", None)
            existing = [n.get("headline") or "" for n in primary]
            # Show Zerodha Pulse at bottom when nothing matched, or as supplement
            if not primary or all(n.get("source") == "demo" for n in primary):
                pulse_feed = pulse_supplement(existing, limit=30)
            elif not any(n.get("source") in ("pulse", "nse") for n in primary):
                pulse_feed = pulse_supplement(existing, limit=25)
            for item in pulse_feed:
                item["severity_tag"] = classify_severity(
                    item.get("headline", ""), exclude_kw, candidate_kw
                )
                item["timing_vs_close"] = timing_vs_market_close(
                    item.get("published_at"), item.get("date")
                )
        return json.dumps({"news": primary, "pulse_feed": pulse_feed})
    finally:
        db.close()


def get_settings_json(db_path: Optional[str] = None) -> str:
    db = init_db(db_path)
    try:
        keys = [
            "z_threshold",
            "min_idio_return",
            "beta_low_threshold",
            "job_hour_ist",
            "require_wifi",
            "require_charging",
            "exclude_keywords",
            "candidate_keywords",
            "hf_token",
        ]
        out = {k: db.get_setting(k) for k in keys}
        token = out.get("hf_token")
        out["hf_token"] = str(token).strip() if token else ""
        out["hf_token_set"] = bool(out["hf_token"])
        out["blueprint_tags"] = load_json_asset("blueprint_tags.json", {})
        return json.dumps(out)
    finally:
        db.close()


def save_settings_json(payload: str, db_path: Optional[str] = None) -> str:
    db = init_db(db_path)
    try:
        data = json.loads(payload)
        for k, v in data.items():
            if k in ("hf_token_set", "hf_token_hint"):
                continue
            if k == "blueprint_tags":
                # Persist override under HOME
                path = os.path.join(os.environ.get("HOME", ""), "blueprint_tags.json")
                with open(path, "w", encoding="utf-8") as f:
                    json.dump(v, f, indent=2)
            elif k == "hf_token":
                db.set_setting("hf_token", str(v).strip() if v is not None else "")
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
