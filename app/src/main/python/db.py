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

    def get_watchlist(self, date: Optional[str] = None) -> List[Dict[str, Any]]:
        cur = self.conn.cursor()
        if date:
            rows = cur.execute(
                "SELECT * FROM watchlist WHERE date = ? ORDER BY conviction_score DESC",
                (date,),
            ).fetchall()
        else:
            latest = cur.execute("SELECT MAX(date) AS d FROM watchlist").fetchone()
            if not latest or not latest["d"]:
                return []
            rows = cur.execute(
                "SELECT * FROM watchlist WHERE date = ? ORDER BY conviction_score DESC",
                (latest["d"],),
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

    def close(self) -> None:
        self.conn.close()
