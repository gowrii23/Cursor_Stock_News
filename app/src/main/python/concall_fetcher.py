"""Fetch concall / transcript metadata from screener.in and optional Gemini qual summary."""
from __future__ import annotations

import json
import logging
import re
import time
from datetime import datetime, timedelta
from html import unescape
from typing import Any, Dict, List, Optional, Tuple

import requests

from db import Database
from progress_report import report

logger = logging.getLogger(__name__)

SCREENER_BASE = "https://www.screener.in"
SCREENER_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    ),
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "en-IN,en;q=0.9",
}
PDF_HEADERS = {
    **SCREENER_HEADERS,
    "Accept": "application/pdf,application/octet-stream,*/*;q=0.8",
    "Referer": "https://www.bseindia.com/",
}
GEMINI_MODEL = "gemini-2.0-flash"
GEMINI_API = (
    f"https://generativelanguage.googleapis.com/v1beta/models/{GEMINI_MODEL}:generateContent"
)
CACHE_DAYS = 7
MAX_TRANSCRIPT_CHARS = 12_000


def _call_with_backoff(fn, *args, max_retries: int = 3, **kwargs):
    last_err = None
    for attempt in range(max_retries):
        try:
            return fn(*args, **kwargs)
        except _RateLimitError as e:
            last_err = e
            if attempt >= max_retries - 1:
                raise
            time.sleep(2 ** attempt)
        except requests.RequestException as e:
            last_err = e
            if attempt >= max_retries - 1:
                raise
            time.sleep(2 ** attempt)
    if last_err:
        raise last_err
    return None


class _RateLimitError(Exception):
    pass


def _strip_html(text: str) -> str:
    return " ".join(unescape(re.sub(r"<[^>]+>", " ", text or "")).split())


def _parse_concall_rows(html: str) -> List[Dict[str, Any]]:
    rows: List[Dict[str, Any]] = []
    for block in re.finditer(
        r'<li class="flex flex-gap-8 flex-wrap-420">(.*?)</li>', html, re.S
    ):
        chunk = block.group(1)
        period_m = re.search(r">([A-Za-z]{3} \d{4})</div>", chunk)
        if not period_m:
            continue
        transcript = re.search(
            r'href="([^"]+)"[^>]*title="Raw Transcript"', chunk
        ) or re.search(r'title="Raw Transcript"[^>]*href="([^"]+)"', chunk)
        summary = re.search(
            r'data-url="(/concalls/summary/\d+/)".*?data-title="Concall Summary',
            chunk,
            re.S,
        ) or re.search(
            r'data-title="Concall Summary[^"]*"[^>]*data-url="([^"]+)"', chunk
        )
        ppt = re.search(r'href="([^"]+)"[^>]*>\s*PPT\s*</a>', chunk)
        rows.append(
            {
                "period": period_m.group(1),
                "transcript_url": transcript.group(1) if transcript else None,
                "summary_path": summary.group(1) if summary else None,
                "ppt_url": ppt.group(1) if ppt else None,
            }
        )
    return rows


def _parse_announcements(html: str) -> List[Dict[str, str]]:
    docs_idx = html.find('id="documents"')
    if docs_idx < 0:
        return []
    chunk = html[docs_idx : docs_idx + 40_000]
    items: List[Dict[str, str]] = []
    for block in re.finditer(
        r'<li class="overflow-wrap-anywhere">(.*?)</li>', chunk, re.S
    ):
        li = block.group(1)
        title_m = re.search(r'>\s*([^<]+?)\s*(?:<div|<span)', li, re.S)
        href_m = re.search(r'href="([^"]+)"', li)
        date_m = re.search(r'datetime="([^"]+)"', li)
        snippet_m = re.search(
            r'class="ink-600 smaller"[^>]*>(.*?)</(?:div|span)>', li, re.S
        )
        title = _strip_html(title_m.group(1) if title_m else "")
        snippet = _strip_html(snippet_m.group(1) if snippet_m else "")
        if not title and not snippet:
            continue
        lower = f"{title} {snippet}".lower()
        if not any(
            k in lower
            for k in (
                "analyst",
                "investor",
                "concall",
                "conference call",
                "earnings",
                "presentation",
                "transcript",
            )
        ):
            continue
        items.append(
            {
                "title": title,
                "date": date_m.group(1)[:10] if date_m else "",
                "snippet": snippet,
                "url": href_m.group(1) if href_m else "",
            }
        )
    return items[:8]


def _fetch_company_html(symbol: str) -> str:
    url = f"{SCREENER_BASE}/company/{symbol.upper()}/"
    resp = requests.get(url, headers=SCREENER_HEADERS, timeout=25)
    resp.raise_for_status()
    return resp.text


def _download_pdf_bytes(url: str) -> Optional[bytes]:
    if not url:
        return None
    try:
        resp = requests.get(url, headers=PDF_HEADERS, timeout=35, allow_redirects=True)
        if resp.status_code == 429:
            raise _RateLimitError("PDF host rate limit")
        if resp.status_code != 200:
            return None
        data = resp.content
        if not data.startswith(b"%PDF"):
            return None
        return data
    except Exception:
        logger.debug("PDF download failed for %s", url, exc_info=True)
        return None


def _extract_pdf_text(data: bytes, max_chars: int = MAX_TRANSCRIPT_CHARS) -> str:
    if not data:
        return ""
    try:
        from pypdf import PdfReader
        import io

        reader = PdfReader(io.BytesIO(data))
        parts: List[str] = []
        total = 0
        for page in reader.pages[:20]:
            text = page.extract_text() or ""
            if not text.strip():
                continue
            parts.append(text)
            total += len(text)
            if total >= max_chars:
                break
        out = "\n".join(parts).strip()
        return out[:max_chars]
    except Exception:
        logger.debug("PDF text extraction failed", exc_info=True)
        return ""


def _summarize_with_gemini(
    transcript_text: str,
    announcements: List[Dict[str, str]],
    gemini_key: str,
) -> Optional[Dict[str, Any]]:
    key = (gemini_key or "").strip()
    if not key:
        return None
    source_bits: List[str] = []
    if announcements:
        source_bits.append("Recent investor announcements:\n" + json.dumps(announcements[:5]))
    if transcript_text:
        source_bits.append("Transcript excerpt:\n" + transcript_text[:10_000])
    if not source_bits:
        return None

    prompt = (
        "You are a value-investing analyst. Use ONLY the source text below.\n"
        "Return a single JSON object (no markdown):\n"
        '{"management_tone":"1 sentence","key_guidance":"1-2 sentences",'
        '"risks_mentioned":"1 sentence","qualitative_flags":"1 sentence"}\n\n'
        + "\n\n".join(source_bits)
    )

    def _post():
        resp = requests.post(
            f"{GEMINI_API}?key={key}",
            json={
                "contents": [{"parts": [{"text": prompt}]}],
                "generationConfig": {
                    "temperature": 0.2,
                    "maxOutputTokens": 400,
                    "responseMimeType": "application/json",
                },
            },
            timeout=40,
        )
        if resp.status_code == 429:
            raise _RateLimitError("Gemini rate limit")
        if resp.status_code != 200:
            return None
        body = resp.json()
        text = ""
        try:
            text = body["candidates"][0]["content"]["parts"][0]["text"]
        except Exception:
            return None
        try:
            return json.loads(text)
        except Exception:
            return {"summary_text": text[:500]}

    try:
        return _call_with_backoff(_post)
    except Exception:
        logger.debug("Gemini qual summary failed", exc_info=True)
        return None


def _pick_latest_concall(rows: List[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    if not rows:
        return None
    for row in rows:
        if row.get("transcript_url"):
            return row
    return rows[0]


def _cache_stale(fetched_at: Optional[str]) -> bool:
    if not fetched_at:
        return True
    try:
        dt = datetime.fromisoformat(fetched_at.replace("Z", ""))
    except Exception:
        return True
    return datetime.utcnow() - dt > timedelta(days=CACHE_DAYS)


def _extract_pdf_text_from_base64(data_b64: str, max_chars: int = MAX_TRANSCRIPT_CHARS) -> str:
    if not data_b64:
        return ""
    try:
        import base64

        raw = base64.b64decode(data_b64)
        return _extract_pdf_text(raw, max_chars=max_chars)
    except Exception:
        logger.debug("base64 PDF extraction failed", exc_info=True)
        return ""


def fetch_transcript_headless(
    transcript_url: Optional[str],
) -> Tuple[str, str]:
    """Returns (text, method) where method is headless | webview_needed | unavailable."""
    if not transcript_url:
        return "", "unavailable"
    pdf_bytes = _download_pdf_bytes(transcript_url)
    if not pdf_bytes:
        return "", "webview_needed"
    text = _extract_pdf_text(pdf_bytes)
    if len(text) > 500:
        return text, "headless"
    return "", "webview_needed"


def build_sources_used(
    record: Dict[str, Any],
    *,
    cached: bool = False,
    qual_status: Optional[str] = None,
) -> Dict[str, Any]:
    return {
        "concall_date": record.get("period"),
        "transcript_chars": int(record.get("transcript_chars") or 0),
        "transcript_method": record.get("transcript_method") or "unavailable",
        "qual_status": qual_status or record.get("qual_status") or "skipped_no_transcript",
        "cached": cached,
    }


def test_gemini_key(gemini_key: str) -> Dict[str, Any]:
    key = (gemini_key or "").strip()
    if not key:
        return {"ok": False, "message": "No Gemini key saved"}
    try:
        resp = requests.post(
            f"{GEMINI_API}?key={key}",
            json={
                "contents": [{"parts": [{"text": "Reply with exactly: OK"}]}],
                "generationConfig": {"temperature": 0, "maxOutputTokens": 8},
            },
            timeout=20,
        )
        if resp.status_code == 429:
            return {"ok": False, "message": "Gemini rate limit — try again shortly"}
        if resp.status_code != 200:
            return {"ok": False, "message": f"Gemini error {resp.status_code}"}
        return {"ok": True, "message": "Gemini key OK"}
    except Exception as e:
        return {"ok": False, "message": str(e)[:120]}


def get_or_fetch_concall(
    db: Database,
    symbol: str,
    gemini_key: str = "",
    force_refresh: bool = False,
    progress_cb: Any = None,
    webview_transcript_text: str = "",
    webview_pdf_base64: str = "",
) -> Dict[str, Any]:
    """Return latest concall context for a symbol (cached up to CACHE_DAYS)."""
    sym = symbol.strip().upper()
    cached = db.get_concall_cache(sym)
    if cached and not force_refresh and not _cache_stale(cached.get("fetched_at")):
        report(progress_cb, 25, "Using cached concall data")
        return cached

    report(progress_cb, 8, "Fetching screener concalls…")
    try:
        html = _fetch_company_html(sym)
    except Exception as e:
        logger.debug("screener fetch failed for %s", sym, exc_info=True)
        return {
            "symbol": sym,
            "status": "fetch_failed",
            "error": str(e)[:120],
            "fetched_at": datetime.utcnow().isoformat(),
        }

    concalls = _parse_concall_rows(html)
    announcements = _parse_announcements(html)
    latest = _pick_latest_concall(concalls)
    period = (latest or {}).get("period")
    transcript_url = (latest or {}).get("transcript_url")
    transcript_text = ""
    transcript_method = "unavailable"
    qual_status = "skipped_no_transcript"

    if webview_pdf_base64:
        report(progress_cb, 22, "Extracting WebView transcript…")
        transcript_text = _extract_pdf_text_from_base64(webview_pdf_base64)
        if len(transcript_text) > 500:
            transcript_method = "webview"
    elif webview_transcript_text and len(webview_transcript_text.strip()) > 500:
        transcript_text = webview_transcript_text.strip()[:MAX_TRANSCRIPT_CHARS]
        transcript_method = "webview"
    elif transcript_url:
        report(progress_cb, 18, f"Downloading transcript ({period or 'latest'})…")
        transcript_text, transcript_method = fetch_transcript_headless(transcript_url)
        if transcript_method == "webview_needed":
            report(progress_cb, 20, "Headless blocked — WebView fallback needed")
            return {
                "symbol": sym,
                "status": "webview_needed",
                "period": period,
                "transcript_url": transcript_url,
                "transcript_method": "webview_needed",
                "transcript_chars": 0,
                "qual_status": "skipped_no_transcript",
                "announcements": announcements,
                "concall_list": concalls[:6],
                "fetched_at": datetime.utcnow().isoformat(),
            }
        if not transcript_text and announcements:
            report(progress_cb, 20, "Transcript PDF unavailable — using announcements")

    qual_summary: Optional[Dict[str, Any]] = None
    has_gemini = bool((gemini_key or "").strip())
    has_qual_input = bool(transcript_text or announcements)

    if has_gemini and has_qual_input:
        report(progress_cb, 30, "Summarizing with Gemini…")
        try:
            qual_summary = _summarize_with_gemini(
                transcript_text, announcements, gemini_key
            )
            if qual_summary:
                qual_status = "used"
            else:
                qual_status = "skipped_gemini_error"
        except _RateLimitError:
            qual_status = "skipped_rate_limit"
        except Exception:
            qual_status = "skipped_gemini_error"
    elif has_qual_input and not has_gemini:
        qual_status = "skipped_no_key"
    elif not has_qual_input:
        qual_status = "skipped_no_transcript"

    record: Dict[str, Any] = {
        "symbol": sym,
        "status": "ok",
        "period": period,
        "transcript_url": transcript_url,
        "transcript_chars": len(transcript_text),
        "transcript_method": transcript_method,
        "qual_status": qual_status,
        "transcript_excerpt": transcript_text[:2500] if transcript_text else "",
        "announcements": announcements,
        "concall_list": concalls[:6],
        "qual_summary": qual_summary,
        "fetched_at": datetime.utcnow().isoformat(),
    }
    db.save_concall_cache(sym, record)
    report(progress_cb, 38, "Concall data ready")
    return record


def concall_payload_for_llm(record: Dict[str, Any]) -> Dict[str, Any]:
    """Slim concall block for the HF Ask AI prompt."""
    if not record or record.get("status") not in ("ok", None):
        return {}
    out: Dict[str, Any] = {}
    if record.get("period"):
        out["period"] = record["period"]
    if record.get("qual_summary"):
        out["qual_summary"] = record["qual_summary"]
    elif record.get("transcript_excerpt"):
        out["transcript_excerpt"] = record["transcript_excerpt"]
    anns = record.get("announcements") or []
    if anns:
        out["recent_investor_announcements"] = anns[:4]
    if record.get("transcript_url") and not record.get("transcript_excerpt"):
        out["transcript_url"] = record["transcript_url"]
        out["note"] = "Transcript linked but text could not be extracted on device."
    return out
