"""Hugging Face Ask AI — synthesize screener scores into a structured verdict."""
from __future__ import annotations

import json
import logging
import re
import time
from datetime import datetime
from typing import Any, Dict, Optional

import requests

from db import Database
from progress_report import report

try:
    from concall_fetcher import (
        build_sources_used,
        concall_payload_for_llm,
        get_or_fetch_concall,
        test_gemini_key,
    )
except Exception:  # pragma: no cover - import guard for partial deploys
    get_or_fetch_concall = None  # type: ignore
    concall_payload_for_llm = None  # type: ignore
    build_sources_used = None  # type: ignore
    test_gemini_key = None  # type: ignore

logger = logging.getLogger(__name__)

# Router chat API — auto-picks whichever inference provider hosts the model.
HF_MODEL = "Qwen/Qwen2.5-7B-Instruct"
HF_API = "https://router.huggingface.co/v1/chat/completions"

SYSTEM_PROMPT = """You are a value-investing analyst. Use ONLY the JSON stock data the user provides.
Quantitative screener scores and optional concall/transcript qualitative context may be included.
Do not invent numbers, prices, or facts that are not in the data.
If concall data is missing, base your verdict on quantitative fields only.
Respond ONLY with a single JSON object (no markdown, no extra text) using this schema:
{"verdict":"BUY_CANDIDATE|WATCH|AVOID","confidence":0-100,"reasoning":"2-3 sentences","key_risk":"1 sentence"}"""

USER_PROMPT_TEMPLATE = """Stock data:
{stock_json}

Respond ONLY with the JSON verdict object."""


def _friendly_api_error(exc_or_msg: Any, status_code: Optional[int] = None) -> str:
    """Map HTTP / network failures to short user-facing text (no tracebacks)."""
    msg = str(exc_or_msg)
    lower = msg.lower()
    if status_code == 401:
        return "Invalid Hugging Face token — check Settings."
    if status_code == 403:
        return "Token cannot access Inference Providers — enable that scope on huggingface.co/settings/tokens."
    if status_code == 429:
        return "Hugging Face rate limit — wait a few minutes and retry."
    if status_code == 410:
        return "Hugging Face API endpoint retired — update the app."
    if status_code == 503:
        return "Model is loading on Hugging Face — retry in a minute."
    if "not supported by provider" in lower or "model_not_supported" in lower:
        return (
            "This model is not on HF's own servers — update the app to use router auto-selection."
        )
    if "inference providers" in lower and ("permission" in lower or "scope" in lower):
        return "HF token needs “Make calls to Inference Providers” — regenerate at huggingface.co/settings/tokens."
    if "failed to resolve" in lower or "name resolution" in lower or "could not resolve host" in lower:
        return "Cannot reach Hugging Face (network/DNS). Check internet connection."
    if "timed out" in lower or "timeout" in lower:
        return "Request timed out — check connection and retry."
    if "max retries exceeded" in lower or "connectionerror" in lower:
        return "Could not connect to Hugging Face — check internet and retry."
    if len(msg) > 160:
        return "Hugging Face request failed — check token and connection."
    return msg


def _extract_error_message(body: Any, fallback: str = "") -> str:
    if isinstance(body, dict):
        err = body.get("error")
        if isinstance(err, dict):
            return str(err.get("message") or err.get("error") or fallback)
        if err:
            return str(err)
    return fallback


def _parse_json_response(body: Any) -> Dict[str, Any]:
    """Extract forced JSON verdict from HF chat-completion payloads."""
    text = ""
    if isinstance(body, dict):
        if body.get("choices"):
            choice = body["choices"][0] if body["choices"] else {}
            message = choice.get("message") if isinstance(choice, dict) else {}
            if isinstance(message, dict):
                text = str(message.get("content") or "")
        elif body.get("generated_text"):
            text = str(body.get("generated_text"))
        elif isinstance(body.get("error"), (str, dict)):
            return {
                "verdict": "ERROR",
                "confidence": 0,
                "reasoning": _friendly_api_error(_extract_error_message(body)),
                "key_risk": "API returned an error payload",
                "cached": False,
            }
    elif isinstance(body, list) and body:
        text = str(body[0].get("generated_text") or body[0].get("summary_text") or "")
    else:
        text = str(body)

    text = text.strip()
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?\s*", "", text)
        text = re.sub(r"\s*```$", "", text)

    # Try whole string first (response_format json_object)
    for candidate in (text,):
        try:
            obj = json.loads(candidate)
            if isinstance(obj, dict) and obj.get("verdict"):
                return _normalize_verdict_obj(obj)
        except Exception:
            pass

    # Prefer last JSON object in the string (prompt may be echoed)
    matches = list(re.finditer(r"\{[^{}]*\}", text, re.S))
    for m in reversed(matches):
        try:
            obj = json.loads(m.group(0))
            if isinstance(obj, dict) and obj.get("verdict"):
                return _normalize_verdict_obj(obj)
        except Exception:
            continue
    return {
        "verdict": "ERROR",
        "confidence": 0,
        "reasoning": "Could not parse AI JSON response",
        "key_risk": "Parse failure — trust your raw L1/L2/L3 scores",
        "cached": False,
    }


def _normalize_verdict_obj(obj: Dict[str, Any]) -> Dict[str, Any]:
    verdict = str(obj.get("verdict") or "ERROR").upper()
    if verdict not in ("BUY_CANDIDATE", "WATCH", "AVOID", "ERROR"):
        verdict = "WATCH"
    try:
        conf_i = int(float(obj.get("confidence")))
    except Exception:
        conf_i = 0
    conf_i = max(0, min(100, conf_i))
    return {
        "verdict": verdict,
        "confidence": conf_i,
        "reasoning": str(obj.get("reasoning") or "").strip() or "No reasoning returned",
        "key_risk": str(obj.get("key_risk") or "").strip() or "—",
        "cached": False,
    }


def build_stock_payload(db: Database, symbol: str) -> Dict[str, Any]:
    """Slim payload from already-computed app data only."""
    sym = symbol.strip().upper()
    payload: Dict[str, Any] = {"symbol": sym}

    screener = db.get_screener_stock(sym)
    if screener:
        payload["source"] = "screener"
        payload["name"] = screener.get("name")
        payload["cmp"] = screener.get("cmp")
        payload["l1_passed"] = screener.get("l1_passed")
        payload["l1_fails"] = screener.get("l1_fails")
        payload["score_total"] = screener.get("score_total")
        payload["tier"] = screener.get("tier")
        payload["score_breakdown"] = screener.get("score_breakdown")
        payload["layer3"] = screener.get("layer3")
        payload["blueprint_tags"] = screener.get("blueprint_tags")
        payload["manual_notes"] = screener.get("manual_notes")
        raw = screener.get("raw_columns") or {}
        if isinstance(raw, dict):
            for label, key in (
                ("P/E", "pe"),
                ("ROCE %", "roce"),
                ("OPM %", "opm"),
                ("Debt / Eq", "debt_eq"),
                ("Div Yld %", "div_yield"),
                ("PEG Ratio", "peg"),
                ("Mar Cap Rs.Cr.", "market_cap"),
                ("FII Hold %", "fii_hold"),
                ("DII Hold %", "dii_hold"),
            ):
                if label in raw and raw[label] not in (None, "", "-", "—"):
                    payload[key] = raw[label]

    try:
        pattas = db.get_pattas_stock(sym)
    except Exception:
        pattas = None
    if pattas:
        payload["pattas_score"] = (pattas.get("pattas") or {}).get("pattas_score")
        payload["pattas_pillars"] = (pattas.get("pattas") or {}).get("pillars")
        payload["pattas_sector"] = pattas.get("sector") or (pattas.get("pattas") or {}).get("sector")

    hist = db.get_stock_watch_history(sym)
    if hist:
        latest = hist[0]
        payload["flag"] = {
            "date": latest.get("date"),
            "severity_tag": latest.get("severity_tag"),
            "z_score": latest.get("z_score"),
            "conviction_score": latest.get("conviction_score"),
            "idiosyncratic_return": latest.get("idiosyncratic_return"),
            "headline": latest.get("headline"),
            "score_breakdown": latest.get("score_breakdown"),
        }

    metrics = db.get_stock_metrics(sym)
    if metrics:
        last = metrics[-1]
        payload["close"] = last.get("close")
        payload["beta_1y"] = last.get("beta_1y")
        payload["alpha_1y"] = last.get("alpha_1y")

    if "name" not in payload:
        universe = {u["ticker"]: u for u in db.get_universe()}
        meta = universe.get(sym) or {}
        payload["name"] = meta.get("name") or sym

    return payload


def get_verdict(
    db: Database,
    symbol: str,
    hf_token: str = "",
    gemini_key: str = "",
    force_refresh: bool = False,
    progress_cb: Any = None,
    webview_transcript_text: str = "",
    webview_pdf_base64: str = "",
) -> Dict[str, Any]:
    """Cached daily HF verdict for a symbol. Manual trigger only."""
    sym = symbol.strip().upper()
    today = datetime.utcnow().date().isoformat()

    token = (hf_token or "").strip()
    if not token:
        return {
            "status": "no_token",
            "verdict": "ERROR",
            "confidence": 0,
            "reasoning": "Add a Hugging Face token in Settings to enable Ask AI.",
            "key_risk": "AI disabled until token is saved",
            "cached": False,
            "symbol": sym,
        }

    if not force_refresh:
        cached = db.get_llm_verdict(sym, today)
        if cached:
            cached["status"] = "ok"
            cached["cached"] = True
            cached["symbol"] = sym
            su = cached.get("sources_used") or {}
            if isinstance(su, dict):
                su = dict(su)
                su["cached"] = True
                cached["sources_used"] = su
            elif build_sources_used:
                cached["sources_used"] = build_sources_used({}, cached=True)
            return cached

    payload = build_stock_payload(db, sym)
    if len(payload.keys()) <= 2:
        return {
            "status": "no_data",
            "verdict": "ERROR",
            "confidence": 0,
            "reasoning": "No screener/flag data for this symbol yet. Run a scan first.",
            "key_risk": "Empty local data",
            "cached": False,
            "symbol": sym,
        }

    concall_record: Dict[str, Any] = {}
    if get_or_fetch_concall and concall_payload_for_llm:
        try:
            concall_record = get_or_fetch_concall(
                db,
                sym,
                gemini_key=gemini_key,
                force_refresh=force_refresh or bool(webview_pdf_base64 or webview_transcript_text),
                progress_cb=progress_cb,
                webview_transcript_text=webview_transcript_text,
                webview_pdf_base64=webview_pdf_base64,
            )
            if concall_record.get("status") == "webview_needed":
                return {
                    "status": "needs_webview",
                    "verdict": "ERROR",
                    "confidence": 0,
                    "reasoning": "Opening transcript in browser view…",
                    "key_risk": "",
                    "cached": False,
                    "symbol": sym,
                    "transcript_url": concall_record.get("transcript_url"),
                    "sources_used": build_sources_used(concall_record, cached=False)
                    if build_sources_used
                    else {},
                }
            concall_block = concall_payload_for_llm(concall_record)
            if concall_block:
                payload["concall"] = concall_block
        except Exception:
            logger.debug("concall fetch skipped for %s", sym, exc_info=True)
            report(progress_cb, 35, "Concall fetch skipped — quant-only verdict")
            concall_record = {}

    sources_used = (
        build_sources_used(concall_record, cached=False)
        if build_sources_used
        else {}
    )
    qual_context = concall_record.get("qual_summary")

    report(progress_cb, 45, "Asking Hugging Face…")
    user_content = USER_PROMPT_TEMPLATE.format(stock_json=json.dumps(payload, default=str))
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
    }
    body = {
        "model": HF_MODEL,
        "provider": "auto",
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": user_content},
        ],
        "max_tokens": 280,
        "temperature": 0.2,
        "response_format": {"type": "json_object"},
    }

    last_err = "Request timed out — check connection and retry."
    for attempt in range(3):
        try:
            resp = requests.post(HF_API, headers=headers, json=body, timeout=45)
            if resp.status_code == 200:
                result = _parse_json_response(resp.json())
                result["status"] = "ok" if result.get("verdict") != "ERROR" else "error"
                result["symbol"] = sym
                result["model"] = HF_MODEL
                result["sources_used"] = sources_used
                if qual_context:
                    result["qual_context"] = qual_context
                if result.get("verdict") != "ERROR":
                    db.save_llm_verdict(sym, today, result)
                return result
            if resp.status_code in (503, 429):
                last_err = _friendly_api_error("", resp.status_code)
                time.sleep(5 * (attempt + 1))
                continue
            try:
                detail = resp.json()
                msg = _extract_error_message(detail, resp.text[:200])
            except Exception:
                msg = resp.text[:200]
            return {
                "status": "error",
                "verdict": "ERROR",
                "confidence": 0,
                "reasoning": _friendly_api_error(msg, resp.status_code),
                "key_risk": "Check HF token has Inference Providers scope",
                "cached": False,
                "symbol": sym,
            }
        except requests.RequestException as e:
            last_err = _friendly_api_error(e)
            time.sleep(3)
            continue

    return {
        "status": "unavailable",
        "verdict": "ERROR",
        "confidence": 0,
        "reasoning": last_err,
        "key_risk": "Use your raw L1/L2/L3 score breakdown above",
        "cached": False,
        "symbol": sym,
    }


def ask_ai_verdict_json(
    symbol: str,
    hf_token: str = "",
    gemini_key: str = "",
    db_path: Optional[str] = None,
    force_refresh: bool = False,
    progress_cb: Any = None,
    webview_transcript_text: str = "",
    webview_pdf_base64: str = "",
) -> str:
    db = Database(db_path)
    try:
        return json.dumps(
            get_verdict(
                db,
                symbol,
                hf_token=hf_token,
                gemini_key=gemini_key,
                force_refresh=force_refresh,
                progress_cb=progress_cb,
                webview_transcript_text=webview_transcript_text,
                webview_pdf_base64=webview_pdf_base64,
            ),
            default=str,
        )
    except Exception as e:
        logger.exception("ask_ai failed")
        return json.dumps(
            {
                "status": "error",
                "verdict": "ERROR",
                "confidence": 0,
                "reasoning": _friendly_api_error(e),
                "key_risk": "Unexpected failure",
                "cached": False,
                "symbol": (symbol or "").upper(),
            }
        )
    finally:
        db.close()


def clear_ai_cache_json(symbol: str, db_path: Optional[str] = None) -> str:
    db = Database(db_path)
    try:
        db.clear_ai_cache(symbol)
        return json.dumps({"status": "ok", "symbol": symbol.upper()})
    finally:
        db.close()


def clear_all_ai_caches_json(db_path: Optional[str] = None) -> str:
    db = Database(db_path)
    try:
        db.clear_all_ai_caches()
        return json.dumps({"status": "ok"})
    finally:
        db.close()


def test_gemini_key_json(gemini_key: str) -> str:
    if not test_gemini_key:
        return json.dumps({"ok": False, "message": "Gemini not available"})
    return json.dumps(test_gemini_key(gemini_key))
