"""SQLite persistence for BSE Blueprint Screener."""
from __future__ import annotations

import json
import os
import sqlite3
from typing import Any, Dict, List, Optional


def _default_db_path() -> str:
    home = os.environ.get("HOME") or os.path.expanduser("~")
    path = os.path.join(home, "bse_blueprint_screener.db")
    return path


class Database:
    def __init__(self, db_path: Optional[str] = None):
        self.db_path = db_path or _default_db_path()
        parent = os.path.dirname(self.db_path)
        if parent:
            os.makedirs(parent, exist_ok=True)
        self.conn = sqlite3.connect(self.db_path)
        self.conn.row_factory = sqlite3.Row
        self._init_schema()
        self._migrate_schema()

    def _migrate_schema(self) -> None:
        cur = self.conn.cursor()
        watch_cols = {row[1] for row in cur.execute("PRAGMA table_info(watchlist)")}
        if "daily_return" not in watch_cols:
            cur.execute("ALTER TABLE watchlist ADD COLUMN daily_return REAL")
        if "score_breakdown" not in watch_cols:
            cur.execute("ALTER TABLE watchlist ADD COLUMN score_breakdown TEXT")
        news_cols = {row[1] for row in cur.execute("PRAGMA table_info(news_cache)")}
        if "published_at" not in news_cols:
            cur.execute("ALTER TABLE news_cache ADD COLUMN published_at TEXT")
        if "timing_vs_close" not in news_cols:
            cur.execute("ALTER TABLE news_cache ADD COLUMN timing_vs_close TEXT")
        scan_cols = {row[1] for row in cur.execute("PRAGMA table_info(screener_scan)")}
        if "low_count" not in scan_cols:
            cur.execute("ALTER TABLE screener_scan ADD COLUMN low_count INTEGER")
        if "top_review" not in scan_cols:
            cur.execute("ALTER TABLE screener_scan ADD COLUMN top_review TEXT")
        if "incomplete_count" not in scan_cols:
            cur.execute("ALTER TABLE screener_scan ADD COLUMN incomplete_count INTEGER")
        swing_run_cols = {row[1] for row in cur.execute("PRAGMA table_info(swing_run)")}
        if swing_run_cols:
            if "as_of" not in swing_run_cols:
                cur.execute("ALTER TABLE swing_run ADD COLUMN as_of TEXT")
            if "regime_state" not in swing_run_cols:
                cur.execute("ALTER TABLE swing_run ADD COLUMN regime_state TEXT")
            if "hit_count" not in swing_run_cols:
                cur.execute("ALTER TABLE swing_run ADD COLUMN hit_count INTEGER")
        swing_hit_cols = {row[1] for row in cur.execute("PRAGMA table_info(swing_hit)")}
        if swing_hit_cols:
            if "also_screens" not in swing_hit_cols:
                cur.execute("ALTER TABLE swing_hit ADD COLUMN also_screens TEXT")
            if "as_of" not in swing_hit_cols:
                cur.execute("ALTER TABLE swing_hit ADD COLUMN as_of TEXT")
        stock_cols = {row[1] for row in cur.execute("PRAGMA table_info(screener_stock)")}
        if stock_cols:
            if "blueprint_tags" not in stock_cols:
                cur.execute("ALTER TABLE screener_stock ADD COLUMN blueprint_tags TEXT")
            if "blueprint_match" not in stock_cols:
                cur.execute("ALTER TABLE screener_stock ADD COLUMN blueprint_match INTEGER")
            if "blueprint_bonus" not in stock_cols:
                cur.execute("ALTER TABLE screener_stock ADD COLUMN blueprint_bonus REAL")
        pattas_scan_cols = {row[1] for row in cur.execute("PRAGMA table_info(pattas_scan)")}
        if pattas_scan_cols:
            if "fields_missing_count" not in pattas_scan_cols:
                cur.execute("ALTER TABLE pattas_scan ADD COLUMN fields_missing_count INTEGER")
            if "scrape_health" not in pattas_scan_cols:
                cur.execute("ALTER TABLE pattas_scan ADD COLUMN scrape_health TEXT")
        pattas_stock_cols = {row[1] for row in cur.execute("PRAGMA table_info(pattas_stock)")}
        if pattas_stock_cols:
            if "sector" not in pattas_stock_cols:
                cur.execute("ALTER TABLE pattas_stock ADD COLUMN sector TEXT")
            if "pillar_count" not in pattas_stock_cols:
                cur.execute("ALTER TABLE pattas_stock ADD COLUMN pillar_count INTEGER")
            if "missing_fields" not in pattas_stock_cols:
                cur.execute("ALTER TABLE pattas_stock ADD COLUMN missing_fields TEXT")
        self.conn.commit()

    def _init_schema(self) -> None:
        cur = self.conn.cursor()
        cur.executescript(
            """
            CREATE TABLE IF NOT EXISTS universe (
              ticker TEXT PRIMARY KEY,
              name TEXT,
              index_membership TEXT,
              aliases TEXT
            );

            CREATE TABLE IF NOT EXISTS daily_metrics (
              ticker TEXT,
              date TEXT,
              close REAL,
              daily_return REAL,
              beta_1y REAL,
              alpha_1y REAL,
              alpha_3y REAL,
              PRIMARY KEY (ticker, date)
            );

            CREATE TABLE IF NOT EXISTS watchlist (
              ticker TEXT,
              date TEXT,
              z_score REAL,
              idiosyncratic_return REAL,
              headline TEXT,
              source TEXT,
              severity_tag TEXT,
              blueprint_tags TEXT,
              conviction_score REAL,
              beta_1y REAL,
              alpha_1y REAL,
              alpha_percentile REAL,
              PRIMARY KEY (ticker, date)
            );

            CREATE TABLE IF NOT EXISTS news_cache (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              ticker TEXT,
              date TEXT,
              headline TEXT,
              source TEXT,
              url TEXT,
              severity_tag TEXT
            );

            CREATE TABLE IF NOT EXISTS run_log (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              started_at TEXT,
              finished_at TEXT,
              status TEXT,
              message TEXT,
              flagged_count INTEGER
            );

            CREATE TABLE IF NOT EXISTS app_settings (
              key TEXT PRIMARY KEY,
              value TEXT
            );

            CREATE TABLE IF NOT EXISTS price_history (
              ticker TEXT,
              date TEXT,
              open REAL,
              high REAL,
              low REAL,
              close REAL,
              volume REAL,
              PRIMARY KEY (ticker, date)
            );

            CREATE TABLE IF NOT EXISTS index_history (
              date TEXT PRIMARY KEY,
              close REAL
            );

            CREATE TABLE IF NOT EXISTS screener_scan (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              scanned_at TEXT,
              source_url TEXT,
              total_raw INTEGER,
              passed_l1 INTEGER,
              high_count INTEGER,
              watch_count INTEGER,
              low_count INTEGER,
              incomplete_count INTEGER,
              top_review TEXT,
              message TEXT
            );

            CREATE TABLE IF NOT EXISTS screener_stock (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              scan_id INTEGER,
              symbol TEXT,
              name TEXT,
              cmp REAL,
              score_total REAL,
              tier TEXT,
              l1_passed INTEGER,
              l1_fails TEXT,
              score_breakdown TEXT,
              layer3 TEXT,
              manual_notes TEXT,
              raw_columns TEXT,
              user_verified INTEGER DEFAULT 0,
              blueprint_tags TEXT,
              blueprint_match INTEGER,
              blueprint_bonus REAL
            );

            CREATE TABLE IF NOT EXISTS swing_run (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              run_at TEXT,
              regime TEXT,
              regime_bullish INTEGER,
              regime_state TEXT,
              as_of TEXT,
              momentum_count INTEGER,
              sleeping_count INTEGER,
              universe_size INTEGER,
              priced_count INTEGER,
              hit_count INTEGER,
              message TEXT
            );

            CREATE TABLE IF NOT EXISTS swing_hit (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              run_id INTEGER,
              symbol TEXT,
              name TEXT,
              screen TEXT,
              close REAL,
              score REAL,
              signals TEXT,
              metrics TEXT,
              also_screens TEXT,
              as_of TEXT
            );

            CREATE TABLE IF NOT EXISTS pattas_symbols (
              symbol TEXT PRIMARY KEY,
              name TEXT,
              added_date TEXT,
              note TEXT
            );

            CREATE TABLE IF NOT EXISTS pattas_scan (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              scanned_at TEXT,
              symbol_count INTEGER,
              message TEXT
            );

            CREATE TABLE IF NOT EXISTS pattas_stock (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              scan_id INTEGER,
              symbol TEXT,
              name TEXT,
              cmp REAL,
              pe REAL,
              div_yield REAL,
              debt_eq REAL,
              roe_3y REAL,
              ind_pe REAL,
              pattas_score INTEGER,
              pillars TEXT,
              peer_medians TEXT,
              peer_group_size INTEGER,
              used_basket_fallback INTEGER,
              user_moat_verified INTEGER DEFAULT 0,
              raw_columns TEXT
            );

            CREATE TABLE IF NOT EXISTS pattas_candidates (
              symbol TEXT PRIMARY KEY,
              name TEXT,
              pattas_score INTEGER,
              pillars TEXT,
              peer_medians TEXT,
              first_seen_date TEXT,
              last_seen_date TEXT
            );

            CREATE TABLE IF NOT EXISTS llm_verdicts (
              symbol TEXT NOT NULL,
              date TEXT NOT NULL,
              verdict TEXT,
              confidence INTEGER,
              reasoning TEXT,
              key_risk TEXT,
              raw TEXT,
              PRIMARY KEY (symbol, date)
            );
            """
        )
        self.conn.commit()

    def upsert_universe(self, rows: List[Dict[str, Any]]) -> None:
        cur = self.conn.cursor()
        for row in rows:
            aliases = row.get("aliases") or []
            if isinstance(aliases, list):
                aliases = json.dumps(aliases)
            cur.execute(
                """
                INSERT INTO universe(ticker, name, index_membership, aliases)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(ticker) DO UPDATE SET
                  name=excluded.name,
                  index_membership=excluded.index_membership,
                  aliases=excluded.aliases
                """,
                (
                    row["ticker"],
                    row.get("name", row["ticker"]),
                    row.get("index_membership", ""),
                    aliases,
                ),
            )
        self.conn.commit()

    def get_universe(self) -> List[Dict[str, Any]]:
        cur = self.conn.cursor()
        rows = cur.execute("SELECT * FROM universe ORDER BY ticker").fetchall()
        out = []
        for r in rows:
            d = dict(r)
            try:
                d["aliases"] = json.loads(d.get("aliases") or "[]")
            except Exception:
                d["aliases"] = []
            out.append(d)
        return out

    def upsert_daily_metrics(self, rows: List[Dict[str, Any]]) -> None:
        cur = self.conn.cursor()
        for row in rows:
            cur.execute(
                """
                INSERT INTO daily_metrics(
                  ticker, date, close, daily_return, beta_1y, alpha_1y, alpha_3y
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(ticker, date) DO UPDATE SET
                  close=excluded.close,
                  daily_return=excluded.daily_return,
                  beta_1y=excluded.beta_1y,
                  alpha_1y=excluded.alpha_1y,
                  alpha_3y=excluded.alpha_3y
                """,
                (
                    row["ticker"],
                    row["date"],
                    row.get("close"),
                    row.get("daily_return"),
                    row.get("beta_1y"),
                    row.get("alpha_1y"),
                    row.get("alpha_3y"),
                ),
            )
        self.conn.commit()

    def replace_watchlist_for_date(self, date: str, rows: List[Dict[str, Any]]) -> None:
        cur = self.conn.cursor()
        cur.execute("DELETE FROM watchlist WHERE date = ?", (date,))
        for row in rows:
            tags = row.get("blueprint_tags") or []
            if isinstance(tags, list):
                tags = json.dumps(tags)
            cur.execute(
                """
                INSERT INTO watchlist(
                  ticker, date, z_score, idiosyncratic_return, headline, source,
                  severity_tag, blueprint_tags, conviction_score, beta_1y,
                  alpha_1y, alpha_percentile, daily_return, score_breakdown
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    row["ticker"],
                    date,
                    row.get("z_score"),
                    row.get("idiosyncratic_return"),
                    row.get("headline"),
                    row.get("source"),
                    row.get("severity_tag"),
                    tags,
                    row.get("conviction_score"),
                    row.get("beta_1y"),
                    row.get("alpha_1y"),
                    row.get("alpha_percentile"),
                    row.get("daily_return"),
                    json.dumps(row.get("score_breakdown"))
                    if row.get("score_breakdown") is not None
                    else None,
                ),
            )
        self.conn.commit()

    def purge_demo_watchlist(self) -> None:
        """Remove synthetic [TEST] rows so they never shadow a real EOD screen."""
        cur = self.conn.cursor()
        cur.execute("DELETE FROM watchlist WHERE ticker = ?", ("[TEST]",))
        cur.execute("DELETE FROM news_cache WHERE ticker = ?", ("[TEST]",))
        self.conn.commit()

    def _best_watchlist_date(self) -> Optional[str]:
        """Latest date with real flags; demo-only dates are skipped when possible."""
        cur = self.conn.cursor()
        dates = [
            r["d"]
            for r in cur.execute(
                "SELECT DISTINCT date AS d FROM watchlist ORDER BY date DESC"
            ).fetchall()
        ]
        if not dates:
            return None
        for d in dates:
            row = cur.execute(
                "SELECT COUNT(*) AS c FROM watchlist WHERE date = ? AND ticker != ?",
                (d, "[TEST]"),
            ).fetchone()
            if row and row["c"] > 0:
                return d
        return dates[0]

    def get_watchlist(self, date: Optional[str] = None) -> List[Dict[str, Any]]:
        cur = self.conn.cursor()
        if date:
            target_date = date
        else:
            target_date = self._best_watchlist_date()
            if not target_date:
                return []
        rows = cur.execute(
            "SELECT * FROM watchlist WHERE date = ? ORDER BY conviction_score DESC",
            (target_date,),
        ).fetchall()
        out = []
        for r in rows:
            d = dict(r)
            try:
                d["blueprint_tags"] = json.loads(d.get("blueprint_tags") or "[]")
            except Exception:
                d["blueprint_tags"] = []
            try:
                raw = d.get("score_breakdown")
                d["score_breakdown"] = json.loads(raw) if raw else None
            except Exception:
                d["score_breakdown"] = None
            out.append(d)
        return out

    def get_stock_metrics(self, ticker: str, limit: int = 260) -> List[Dict[str, Any]]:
        cur = self.conn.cursor()
        rows = cur.execute(
            """
            SELECT * FROM daily_metrics
            WHERE ticker = ?
            ORDER BY date DESC
            LIMIT ?
            """,
            (ticker, limit),
        ).fetchall()
        return [dict(r) for r in reversed(rows)]

    def get_stock_watch_history(self, ticker: str) -> List[Dict[str, Any]]:
        cur = self.conn.cursor()
        rows = cur.execute(
            """
            SELECT * FROM watchlist
            WHERE ticker = ?
            ORDER BY date DESC
            LIMIT 60
            """,
            (ticker,),
        ).fetchall()
        out = []
        for r in rows:
            d = dict(r)
            try:
                d["blueprint_tags"] = json.loads(d.get("blueprint_tags") or "[]")
            except Exception:
                d["blueprint_tags"] = []
            try:
                raw = d.get("score_breakdown")
                d["score_breakdown"] = json.loads(raw) if raw else None
            except Exception:
                d["score_breakdown"] = None
            out.append(d)
        return out

    def insert_news(self, rows: List[Dict[str, Any]]) -> None:
        cur = self.conn.cursor()
        for row in rows:
            cur.execute(
                """
                INSERT INTO news_cache(
                  ticker, date, headline, source, url, severity_tag,
                  published_at, timing_vs_close
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    row.get("ticker"),
                    row.get("date"),
                    row.get("headline"),
                    row.get("source"),
                    row.get("url"),
                    row.get("severity_tag"),
                    row.get("published_at"),
                    row.get("timing_vs_close"),
                ),
            )
        self.conn.commit()

    def get_news(self, ticker: Optional[str] = None, limit: int = 100) -> List[Dict[str, Any]]:
        cur = self.conn.cursor()
        if ticker:
            rows = cur.execute(
                """
                SELECT * FROM news_cache
                WHERE ticker = ?
                ORDER BY id DESC LIMIT ?
                """,
                (ticker, limit),
            ).fetchall()
        else:
            rows = cur.execute(
                "SELECT * FROM news_cache ORDER BY id DESC LIMIT ?",
                (limit,),
            ).fetchall()
        return [dict(r) for r in rows]

    def log_run(self, started_at: str, finished_at: str, status: str, message: str, flagged: int) -> None:
        cur = self.conn.cursor()
        cur.execute(
            """
            INSERT INTO run_log(started_at, finished_at, status, message, flagged_count)
            VALUES (?, ?, ?, ?, ?)
            """,
            (started_at, finished_at, status, message, flagged),
        )
        self.conn.commit()

    def latest_run(self) -> Optional[Dict[str, Any]]:
        cur = self.conn.cursor()
        row = cur.execute("SELECT * FROM run_log ORDER BY id DESC LIMIT 1").fetchone()
        return dict(row) if row else None

    def upsert_price_history(self, rows: List[Dict[str, Any]]) -> None:
        cur = self.conn.cursor()
        for row in rows:
            cur.execute(
                """
                INSERT INTO price_history(ticker, date, open, high, low, close, volume)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(ticker, date) DO UPDATE SET
                  open=excluded.open,
                  high=excluded.high,
                  low=excluded.low,
                  close=excluded.close,
                  volume=excluded.volume
                """,
                (
                    row["ticker"],
                    row["date"],
                    row.get("open"),
                    row.get("high"),
                    row.get("low"),
                    row.get("close"),
                    row.get("volume"),
                ),
            )
        self.conn.commit()

    def upsert_index_history(self, day: str, close: float) -> None:
        cur = self.conn.cursor()
        cur.execute(
            """
            INSERT INTO index_history(date, close) VALUES (?, ?)
            ON CONFLICT(date) DO UPDATE SET close=excluded.close
            """,
            (day, close),
        )
        self.conn.commit()

    def get_cached_price_dates(self) -> List[str]:
        cur = self.conn.cursor()
        rows = cur.execute(
            "SELECT DISTINCT date FROM price_history ORDER BY date"
        ).fetchall()
        return [r["date"] for r in rows]

    def count_tickers_for_date(self, day: str) -> int:
        cur = self.conn.cursor()
        row = cur.execute(
            "SELECT COUNT(DISTINCT ticker) AS c FROM price_history WHERE date = ?",
            (day,),
        ).fetchone()
        return int(row["c"]) if row else 0

    def get_price_history(self, ticker: str, limit: int = 400) -> List[Dict[str, Any]]:
        cur = self.conn.cursor()
        rows = cur.execute(
            """
            SELECT date, open, high, low, close, volume
            FROM price_history
            WHERE ticker = ?
            ORDER BY date DESC
            LIMIT ?
            """,
            (ticker.upper(), limit),
        ).fetchall()
        return [dict(r) for r in reversed(rows)]

    def get_index_history(self, limit: int = 400) -> List[Dict[str, Any]]:
        cur = self.conn.cursor()
        rows = cur.execute(
            "SELECT date, close FROM index_history ORDER BY date DESC LIMIT ?",
            (limit,),
        ).fetchall()
        return [dict(r) for r in reversed(rows)]

    def save_screener_scan(
        self,
        meta: Dict[str, Any],
        stocks: List[Dict[str, Any]],
    ) -> int:
        cur = self.conn.cursor()
        cur.execute(
            """
            INSERT INTO screener_scan(
              scanned_at, source_url, total_raw, passed_l1, high_count, watch_count,
              low_count, incomplete_count, top_review, message
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                meta.get("scanned_at"),
                meta.get("source_url"),
                meta.get("total_raw"),
                meta.get("passed_l1"),
                meta.get("high_count"),
                meta.get("watch_count"),
                meta.get("low_count"),
                meta.get("incomplete_count"),
                json.dumps(meta.get("top_review") or []),
                meta.get("message"),
            ),
        )
        scan_id = cur.lastrowid
        for s in stocks:
            cur.execute(
                """
                INSERT INTO screener_stock(
                  scan_id, symbol, name, cmp, score_total, tier, l1_passed,
                  l1_fails, score_breakdown, layer3, manual_notes, raw_columns,
                  blueprint_tags, blueprint_match, blueprint_bonus
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    scan_id,
                    s.get("symbol"),
                    s.get("name"),
                    s.get("cmp"),
                    s.get("score_total"),
                    s.get("tier"),
                    1 if s.get("l1_passed") else 0,
                    json.dumps(s.get("l1_fails") or []),
                    json.dumps(s.get("score_breakdown") or {}),
                    json.dumps(s.get("layer3") or {}),
                    json.dumps(s.get("manual_notes") or []),
                    json.dumps(s.get("raw") or {}),
                    json.dumps(s.get("blueprint_tags") or []),
                    1 if s.get("blueprint_match") else 0,
                    float(s.get("blueprint_bonus") or 0),
                ),
            )
        self.conn.commit()
        return int(scan_id)

    def latest_screener_scan(self) -> Optional[Dict[str, Any]]:
        cur = self.conn.cursor()
        row = cur.execute(
            "SELECT * FROM screener_scan ORDER BY id DESC LIMIT 1"
        ).fetchone()
        if not row:
            return None
        d = dict(row)
        if d.get("top_review"):
            try:
                d["top_review"] = json.loads(d["top_review"])
            except Exception:
                d["top_review"] = []
        else:
            d["top_review"] = []
        return d

    def get_screener_stocks(
        self,
        scan_id: Optional[int] = None,
        tier: Optional[str] = None,
    ) -> List[Dict[str, Any]]:
        cur = self.conn.cursor()
        if scan_id is None:
            latest = self.latest_screener_scan()
            if not latest:
                return []
            scan_id = latest["id"]
        sql = """
            SELECT * FROM screener_stock
            WHERE scan_id = ? AND l1_passed = 1
        """
        params: List[Any] = [scan_id]
        if tier and tier != "all":
            sql += " AND tier = ?"
            params.append(tier)
        sql += " ORDER BY score_total DESC"
        rows = cur.execute(sql, params).fetchall()
        out = []
        for r in rows:
            d = dict(r)
            for key in ("l1_fails", "score_breakdown", "layer3", "manual_notes", "raw_columns"):
                try:
                    d[key] = json.loads(d.get(key) or "null")
                except Exception:
                    d[key] = None
            try:
                d["blueprint_tags"] = json.loads(d.get("blueprint_tags") or "[]")
            except Exception:
                d["blueprint_tags"] = []
            d["blueprint_match"] = bool(d.get("blueprint_match"))
            out.append(d)
        return out

    def get_screener_stock(self, symbol: str, scan_id: Optional[int] = None) -> Optional[Dict[str, Any]]:
        cur = self.conn.cursor()
        if scan_id is None:
            latest = self.latest_screener_scan()
            if not latest:
                return None
            scan_id = latest["id"]
        row = cur.execute(
            """
            SELECT * FROM screener_stock
            WHERE scan_id = ? AND symbol = ?
            """,
            (scan_id, symbol.upper()),
        ).fetchone()
        if not row:
            return None
        d = dict(row)
        for key in ("l1_fails", "score_breakdown", "layer3", "manual_notes", "raw_columns"):
            try:
                d[key] = json.loads(d.get(key) or "null")
            except Exception:
                d[key] = None
        try:
            d["blueprint_tags"] = json.loads(d.get("blueprint_tags") or "[]")
        except Exception:
            d["blueprint_tags"] = []
        d["blueprint_match"] = bool(d.get("blueprint_match"))
        return d

    def set_screener_verified(self, symbol: str, verified: bool) -> None:
        cur = self.conn.cursor()
        latest = self.latest_screener_scan()
        if not latest:
            return
        cur.execute(
            """
            UPDATE screener_stock SET user_verified = ?
            WHERE scan_id = ? AND symbol = ?
            """,
            (1 if verified else 0, latest["id"], symbol.upper()),
        )
        self.conn.commit()

    def save_swing_run(self, meta: Dict[str, Any], hits: List[Dict[str, Any]]) -> int:
        cur = self.conn.cursor()
        cur.execute(
            """
            INSERT INTO swing_run(
              run_at, regime, regime_bullish, regime_state, as_of,
              momentum_count, sleeping_count, universe_size, priced_count, hit_count, message
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                meta.get("run_at"),
                meta.get("regime"),
                meta.get("regime_bullish"),
                meta.get("regime_state"),
                meta.get("as_of"),
                meta.get("momentum_count"),
                meta.get("sleeping_count"),
                meta.get("universe_size"),
                meta.get("priced_count"),
                meta.get("hit_count"),
                meta.get("message"),
            ),
        )
        run_id = cur.lastrowid
        for h in hits:
            cur.execute(
                """
                INSERT INTO swing_hit(
                  run_id, symbol, name, screen, close, score, signals, metrics, also_screens, as_of
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    run_id,
                    h.get("symbol"),
                    h.get("name"),
                    h.get("screen"),
                    h.get("close"),
                    h.get("score"),
                    json.dumps(h.get("signals") or []),
                    json.dumps(h.get("metrics") or {}),
                    json.dumps(h.get("also_screens") or []),
                    h.get("as_of"),
                ),
            )
        self.conn.commit()
        return int(run_id)

    def latest_swing_run(self) -> Optional[Dict[str, Any]]:
        cur = self.conn.cursor()
        row = cur.execute(
            "SELECT * FROM swing_run ORDER BY id DESC LIMIT 1"
        ).fetchone()
        return dict(row) if row else None

    def get_swing_hits(
        self,
        run_id: Optional[int] = None,
        screen: Optional[str] = None,
    ) -> List[Dict[str, Any]]:
        cur = self.conn.cursor()
        if run_id is None:
            latest = self.latest_swing_run()
            if not latest:
                return []
            run_id = latest["id"]
        sql = "SELECT * FROM swing_hit WHERE run_id = ?"
        params: List[Any] = [run_id]
        if screen and screen != "all":
            sql += " AND screen = ?"
            params.append(screen)
        sql += " ORDER BY score DESC"
        rows = cur.execute(sql, params).fetchall()
        out = []
        for r in rows:
            d = dict(r)
            for key in ("signals", "metrics", "also_screens"):
                try:
                    d[key] = json.loads(d.get(key) or "null")
                except Exception:
                    d[key] = None
            out.append(d)
        return out

    def get_swing_hit(self, symbol: str, run_id: Optional[int] = None) -> Optional[Dict[str, Any]]:
        cur = self.conn.cursor()
        if run_id is None:
            latest = self.latest_swing_run()
            if not latest:
                return None
            run_id = latest["id"]
        row = cur.execute(
            "SELECT * FROM swing_hit WHERE run_id = ? AND symbol = ?",
            (run_id, symbol.upper()),
        ).fetchone()
        if not row:
            return None
        d = dict(row)
        for key in ("signals", "metrics", "also_screens"):
            try:
                d[key] = json.loads(d.get(key) or "null")
            except Exception:
                d[key] = None
        return d

    def seed_pattas_symbols_if_empty(self) -> None:
        if self.get_pattas_symbols():
            return
        from datetime import datetime as _dt
        from pipeline import load_json_asset

        seed = load_json_asset("pattas_universe.json", [])
        today = _dt.utcnow().date().isoformat()
        for entry in seed:
            if isinstance(entry, dict):
                sym = str(entry.get("symbol") or "").upper()
                name = entry.get("name")
            else:
                sym = str(entry).upper()
                name = None
            if sym:
                self.add_pattas_symbol(sym, name=name, note="seed", added_date=today)

    def get_pattas_symbols(self) -> List[Dict[str, Any]]:
        cur = self.conn.cursor()
        rows = cur.execute(
            "SELECT symbol, name, added_date, note FROM pattas_symbols ORDER BY symbol"
        ).fetchall()
        return [dict(r) for r in rows]

    def add_pattas_symbol(
        self,
        symbol: str,
        name: Optional[str] = None,
        note: Optional[str] = None,
        added_date: Optional[str] = None,
    ) -> None:
        sym = symbol.strip().upper()
        if not sym:
            return
        from datetime import datetime as _dt

        cur = self.conn.cursor()
        cur.execute(
            """
            INSERT INTO pattas_symbols(symbol, name, added_date, note)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(symbol) DO UPDATE SET
              name=COALESCE(excluded.name, pattas_symbols.name),
              note=COALESCE(excluded.note, pattas_symbols.note)
            """,
            (sym, name, added_date or _dt.utcnow().date().isoformat(), note),
        )
        self.conn.commit()

    def remove_pattas_symbol(self, symbol: str) -> None:
        cur = self.conn.cursor()
        cur.execute("DELETE FROM pattas_symbols WHERE symbol = ?", (symbol.upper(),))
        self.conn.commit()

    def save_pattas_scan(
        self,
        meta: Dict[str, Any],
        stocks: List[Dict[str, Any]],
    ) -> int:
        cur = self.conn.cursor()
        cur.execute(
            """
            INSERT INTO pattas_scan(
              scanned_at, symbol_count, message, fields_missing_count, scrape_health
            )
            VALUES (?, ?, ?, ?, ?)
            """,
            (
                meta.get("scanned_at"),
                meta.get("symbol_count") or len(stocks),
                meta.get("message"),
                meta.get("fields_missing_count"),
                json.dumps(meta.get("scrape_health") or {}),
            ),
        )
        scan_id = cur.lastrowid
        for s in stocks:
            pattas = s.get("pattas") or {}
            cur.execute(
                """
                INSERT INTO pattas_stock(
                  scan_id, symbol, name, cmp, pe, div_yield, debt_eq, roe_3y, ind_pe,
                  pattas_score, pillars, peer_medians, peer_group_size,
                  used_basket_fallback, user_moat_verified, raw_columns,
                  sector, pillar_count, missing_fields
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    scan_id,
                    s.get("symbol"),
                    s.get("name"),
                    s.get("cmp"),
                    s.get("pe"),
                    s.get("div_yield"),
                    s.get("debt_eq"),
                    s.get("roe_3y"),
                    s.get("ind_pe"),
                    pattas.get("pattas_score", 0),
                    json.dumps(pattas.get("pillars") or {}),
                    json.dumps(pattas.get("peer_medians") or {}),
                    pattas.get("peer_group_size", 0),
                    1 if pattas.get("used_basket_fallback") else 0,
                    1 if s.get("user_moat_verified") else 0,
                    json.dumps(s.get("raw") or {}),
                    pattas.get("sector") or s.get("sector"),
                    pattas.get("pillar_count"),
                    json.dumps(pattas.get("missing_fields") or []),
                ),
            )
        self.conn.commit()
        return int(scan_id)

    def latest_pattas_scan(self) -> Optional[Dict[str, Any]]:
        cur = self.conn.cursor()
        row = cur.execute(
            "SELECT * FROM pattas_scan ORDER BY id DESC LIMIT 1"
        ).fetchone()
        return dict(row) if row else None

    def get_pattas_stocks(self, scan_id: Optional[int] = None) -> List[Dict[str, Any]]:
        cur = self.conn.cursor()
        if scan_id is None:
            latest = self.latest_pattas_scan()
            if not latest:
                return []
            scan_id = latest["id"]
        rows = cur.execute(
            """
            SELECT * FROM pattas_stock
            WHERE scan_id = ?
            ORDER BY pattas_score DESC, symbol
            """,
            (scan_id,),
        ).fetchall()
        out = []
        for r in rows:
            d = dict(r)
            for key in ("pillars", "peer_medians", "raw_columns", "missing_fields"):
                try:
                    d[key] = json.loads(d.get(key) or "null")
                except Exception:
                    d[key] = None
            d["used_basket_fallback"] = bool(d.get("used_basket_fallback"))
            d["user_moat_verified"] = bool(d.get("user_moat_verified"))
            raw = d.get("raw_columns")
            if isinstance(raw, dict) and raw:
                try:
                    from pattas_engine import enrich_pattas_row

                    enriched = enrich_pattas_row(
                        {"symbol": d.get("symbol"), "raw": raw, **raw}
                    )
                    for key in (
                        "pb",
                        "net_npa",
                        "gross_npa",
                        "fcf_yield",
                        "sector",
                        "pe",
                        "div_yield",
                        "debt_eq",
                        "roe_3y",
                    ):
                        if enriched.get(key) is not None:
                            d[key] = enriched[key]
                except Exception:
                    pass
            pattas = {
                "pattas_score": d.get("pattas_score", 0),
                "pillars": d.get("pillars") or {},
                "peer_medians": d.get("peer_medians") or {},
                "peer_group_size": d.get("peer_group_size", 0),
                "used_basket_fallback": d.get("used_basket_fallback", False),
                "sector": d.get("sector"),
                "pillar_count": d.get("pillar_count"),
                "missing_fields": d.get("missing_fields") or [],
            }
            d["pillar_count"] = d.get("pillar_count") or pattas.get("pillar_count")
            d["pattas"] = pattas
            out.append(d)
        return out

    def get_pattas_stock(self, symbol: str, scan_id: Optional[int] = None) -> Optional[Dict[str, Any]]:
        stocks = self.get_pattas_stocks(scan_id=scan_id)
        sym = symbol.upper()
        for s in stocks:
            if s.get("symbol") == sym:
                return s
        return None

    def set_pattas_moat_verified(self, symbol: str, verified: bool) -> None:
        cur = self.conn.cursor()
        latest = self.latest_pattas_scan()
        if not latest:
            return
        cur.execute(
            """
            UPDATE pattas_stock SET user_moat_verified = ?
            WHERE scan_id = ? AND symbol = ?
            """,
            (1 if verified else 0, latest["id"], symbol.upper()),
        )
        self.conn.commit()

    def save_pattas_candidates(self, candidates: List[Dict[str, Any]]) -> None:
        from datetime import datetime as _dt

        today = _dt.utcnow().date().isoformat()
        cur = self.conn.cursor()
        seen = set()
        for c in candidates:
            sym = (c.get("symbol") or "").upper()
            if not sym:
                continue
            seen.add(sym)
            pattas = c.get("pattas") or {}
            existing = cur.execute(
                "SELECT first_seen_date FROM pattas_candidates WHERE symbol = ?",
                (sym,),
            ).fetchone()
            first = existing["first_seen_date"] if existing else today
            cur.execute(
                """
                INSERT INTO pattas_candidates(
                  symbol, name, pattas_score, pillars, peer_medians,
                  first_seen_date, last_seen_date
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(symbol) DO UPDATE SET
                  name=excluded.name,
                  pattas_score=excluded.pattas_score,
                  pillars=excluded.pillars,
                  peer_medians=excluded.peer_medians,
                  last_seen_date=excluded.last_seen_date
                """,
                (
                    sym,
                    c.get("name"),
                    pattas.get("pattas_score", 0),
                    json.dumps(pattas.get("pillars") or {}),
                    json.dumps(pattas.get("peer_medians") or {}),
                    first,
                    today,
                ),
            )
        if seen:
            placeholders = ",".join("?" for _ in seen)
            cur.execute(
                f"DELETE FROM pattas_candidates WHERE symbol NOT IN ({placeholders})",
                tuple(seen),
            )
        else:
            cur.execute("DELETE FROM pattas_candidates")
        self.conn.commit()

    def get_pattas_candidates(self) -> List[Dict[str, Any]]:
        cur = self.conn.cursor()
        rows = cur.execute(
            """
            SELECT * FROM pattas_candidates
            ORDER BY pattas_score DESC, symbol
            """
        ).fetchall()
        out = []
        for r in rows:
            d = dict(r)
            for key in ("pillars", "peer_medians"):
                try:
                    d[key] = json.loads(d.get(key) or "null")
                except Exception:
                    d[key] = None
            out.append(d)
        return out

    def set_setting(self, key: str, value: Any) -> None:
        cur = self.conn.cursor()
        cur.execute(
            """
            INSERT INTO app_settings(key, value) VALUES (?, ?)
            ON CONFLICT(key) DO UPDATE SET value=excluded.value
            """,
            (key, json.dumps(value)),
        )
        self.conn.commit()

    def get_setting(self, key: str, default: Any = None) -> Any:
        cur = self.conn.cursor()
        row = cur.execute("SELECT value FROM app_settings WHERE key = ?", (key,)).fetchone()
        if not row:
            return default
        try:
            return json.loads(row["value"])
        except Exception:
            return row["value"]

    def save_llm_verdict(self, symbol: str, date: str, result: Dict[str, Any]) -> None:
        cur = self.conn.cursor()
        cur.execute(
            """
            INSERT INTO llm_verdicts(
              symbol, date, verdict, confidence, reasoning, key_risk, raw
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(symbol, date) DO UPDATE SET
              verdict=excluded.verdict,
              confidence=excluded.confidence,
              reasoning=excluded.reasoning,
              key_risk=excluded.key_risk,
              raw=excluded.raw
            """,
            (
                symbol.upper(),
                date,
                result.get("verdict"),
                result.get("confidence"),
                result.get("reasoning"),
                result.get("key_risk"),
                json.dumps(result, default=str),
            ),
        )
        self.conn.commit()

    def get_llm_verdict(self, symbol: str, date: str) -> Optional[Dict[str, Any]]:
        cur = self.conn.cursor()
        row = cur.execute(
            """
            SELECT verdict, confidence, reasoning, key_risk, raw
            FROM llm_verdicts
            WHERE symbol = ? AND date = ?
            """,
            (symbol.upper(), date),
        ).fetchone()
        if not row:
            return None
        d = dict(row)
        return {
            "verdict": d.get("verdict"),
            "confidence": d.get("confidence") or 0,
            "reasoning": d.get("reasoning") or "",
            "key_risk": d.get("key_risk") or "",
        }

    def close(self) -> None:
        self.conn.close()
