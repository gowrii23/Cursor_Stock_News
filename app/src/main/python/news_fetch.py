"""News fetch from Zerodha Pulse RSS + NSE corporate announcements."""
from __future__ import annotations

import logging
import re
from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional
from xml.etree import ElementTree as ET

import requests

logger = logging.getLogger(__name__)

PULSE_FEED = "https://pulse.zerodha.com/feed.php"
NSE_ANNOUNCEMENTS = (
    "https://www.nseindia.com/api/corporate-announcements"
    "?index=equities&from_date={from_date}&to_date={to_date}"
)

# Single synthetic row for offline / demo mode
DEMO_TICKER = "[TEST]"

_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    ),
    "Accept": "application/rss+xml, application/xml, text/xml, application/json, */*",
}


def fetch_pulse_headlines(limit: int = 200) -> List[Dict[str, Any]]:
    items: List[Dict[str, Any]] = []
    try:
        resp = requests.get(PULSE_FEED, headers=_HEADERS, timeout=20)
        resp.raise_for_status()
        # Prefer feedparser when available
        try:
            import feedparser

            feed = feedparser.parse(resp.content)
            for e in feed.entries[:limit]:
                published = _parse_date(
                    getattr(e, "published", None) or getattr(e, "updated", None)
                )
                published_at = _parse_datetime_iso(
                    getattr(e, "published", None) or getattr(e, "updated", None)
                )
                items.append(
                    {
                        "headline": getattr(e, "title", "") or "",
                        "source": "pulse",
                        "url": getattr(e, "link", "") or "",
                        "date": published,
                        "published_at": published_at,
                    }
                )
        except Exception:
            items = _parse_rss_xml(resp.text, limit=limit, source="pulse")
    except Exception as e:
        logger.warning("Pulse fetch failed: %s", e)
    return items


def fetch_nse_announcements(
    from_date: Optional[str] = None, to_date: Optional[str] = None
) -> List[Dict[str, Any]]:
    """Best-effort NSE announcements; may fail due to cookie wall — returns []."""
    today = datetime.utcnow().date()
    if not to_date:
        to_date = today.strftime("%d-%m-%Y")
    if not from_date:
        from_date = (today - timedelta(days=2)).strftime("%d-%m-%Y")
    url = NSE_ANNOUNCEMENTS.format(from_date=from_date, to_date=to_date)
    items: List[Dict[str, Any]] = []
    try:
        session = requests.Session()
        session.headers.update(_HEADERS)
        session.headers["Referer"] = "https://www.nseindia.com/"
        # Warm-up cookies
        session.get("https://www.nseindia.com/", timeout=15)
        resp = session.get(url, timeout=20)
        if resp.status_code != 200:
            logger.warning("NSE announcements HTTP %s", resp.status_code)
            return items
        data = resp.json()
        rows = data if isinstance(data, list) else data.get("data", [])
        for row in rows:
            sym = row.get("symbol") or row.get("sm_name") or ""
            headline = row.get("desc") or row.get("subject") or row.get("attchmntText") or ""
            dt = row.get("an_dt") or row.get("date") or today.isoformat()
            items.append(
                {
                    "ticker": sym,
                    "headline": headline,
                    "source": "nse",
                    "url": row.get("attchmntFile") or "",
                    "date": _normalize_date(dt),
                }
            )
    except Exception as e:
        logger.warning("NSE announcements failed: %s", e)
    return items


def match_headlines_to_universe(
    headlines: List[Dict[str, Any]],
    universe: List[Dict[str, Any]],
) -> List[Dict[str, Any]]:
    """Attach ticker when headline mentions ticker/name/alias."""
    matched: List[Dict[str, Any]] = []
    for h in headlines:
        text = (h.get("headline") or "").lower()
        if h.get("ticker"):
            matched.append(h)
            continue
        for u in universe:
            ticker = u["ticker"]
            names = [ticker.lower(), (u.get("name") or "").lower()]
            names.extend([a.lower() for a in (u.get("aliases") or [])])
            names = [n for n in names if n]
            if any(_contains_token(text, n) for n in names):
                item = dict(h)
                item["ticker"] = ticker
                matched.append(item)
                break
    return matched


def demo_headlines(
    flagged_tickers: Optional[List[str]] = None,
    universe: Optional[List[Dict[str, Any]]] = None,
) -> List[Dict[str, Any]]:
    """Single synthetic headline for offline / demo mode — one [TEST] row only."""
    _ = flagged_tickers, universe  # legacy signature kept for callers
    today = datetime.utcnow().date().isoformat()
    return [
        {
            "ticker": DEMO_TICKER,
            "headline": (
                "[TEST] synthetic mock — brokerage downgrade after muted quarter "
                "(offline demo, not live news)"
            ),
            "source": "demo",
            "url": "",
            "date": today,
        }
    ]


def pulse_supplement(
    existing_headlines: List[str],
    limit: int = 30,
) -> List[Dict[str, Any]]:
    """Zerodha Pulse headlines not already in the primary news list."""
    seen = {h.strip().lower() for h in existing_headlines if h}
    out: List[Dict[str, Any]] = []
    for item in fetch_pulse_headlines(limit=limit + len(seen)):
        headline = (item.get("headline") or "").strip()
        if not headline or headline.lower() in seen:
            continue
        out.append(item)
        if len(out) >= limit:
            break
    return out


def _contains_token(text: str, needle: str) -> bool:
    if not needle or len(needle) < 2:
        return False
    if " " in needle or "&" in needle or "-" in needle:
        return needle in text
    return re.search(rf"\b{re.escape(needle)}\b", text) is not None


def _parse_rss_xml(xml_text: str, limit: int, source: str) -> List[Dict[str, Any]]:
    items = []
    try:
        root = ET.fromstring(xml_text)
        for item in root.findall(".//item")[:limit]:
            title = (item.findtext("title") or "").strip()
            link = (item.findtext("link") or "").strip()
            pub = item.findtext("pubDate")
            items.append(
                {
                    "headline": title,
                    "source": source,
                    "url": link,
                    "date": _parse_date(pub),
                }
            )
    except Exception as e:
        logger.warning("RSS XML parse failed: %s", e)
    return items


def _parse_datetime_iso(value: Optional[str]) -> Optional[str]:
    if not value:
        return None
    try:
        import email.utils

        dt = email.utils.parsedate_to_datetime(value.strip())
        return dt.isoformat()
    except Exception:
        pass
    for fmt in (
        "%Y-%m-%dT%H:%M:%S%z",
        "%a, %d %b %Y %H:%M:%S %z",
    ):
        try:
            return datetime.strptime(value.strip(), fmt).isoformat()
        except Exception:
            continue
    return None


def _parse_date(value: Optional[str]) -> str:
    if not value:
        return datetime.utcnow().date().isoformat()
    for fmt in (
        "%a, %d %b %Y %H:%M:%S %z",
        "%a, %d %b %Y %H:%M:%S %Z",
        "%Y-%m-%dT%H:%M:%S%z",
        "%Y-%m-%d",
        "%d-%m-%Y %H:%M:%S",
        "%d-%b-%Y",
    ):
        try:
            return datetime.strptime(value.strip(), fmt).date().isoformat()
        except Exception:
            continue
    return datetime.utcnow().date().isoformat()


def _normalize_date(value: str) -> str:
    return _parse_date(str(value))
