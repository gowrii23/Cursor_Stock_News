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

logger = logging.getLogger(__name__)

# Prefer a publicly reachable Instruct model on HF Inference.
HF_MODEL = "mistralai/Mistral-7B-Instruct-v0.3"
HF_API = f"https://api-inference.huggingface.co/models/{HF_MODEL}"

PROMPT_TEMPLATE = """You are a value-investing analyst. Use ONLY the JSON stock data below.
Do not invent numbers, prices, or facts that are not in the data.
Do not cite figures you were not given.

Stock data:
{stock_json}

Respond ONLY with a single JSON object (no markdown, no extra text):
{{"verdict":"BUY_CANDIDATE|WATCH|AVOID","confidence":0-100,"reasoning":"2-3 sentences","key_risk":"1 sentence"}}"""


def _parse_json_response(body: Any) -> Dict[str, Any]:
    """Extract forced JSON verdict from HF text-generation payloads."""
    text = ""
    if isinstance(body, list) and body:
        text = str(body[0].get("generated_text") or body[0].get("summary_text") or "")
    elif isinstance(body, dict):
        text = str(body.get("generated_text") or body.get("error") or "")
        if body.get("error") and not body.get("generated_text"):
            return {
                "verdict": "ERROR",
                "confidence": 0,
                "reasoning": str(body.get("error")),
                "key_risk": "API returned an error payload",
                "cached": False,
            }
    else:
        text = str(body)

    # Prefer last JSON object in the string (prompt may be echoed)
    matches = list(re.finditer(r"\{[^{}]*\}", text, re.S))
    for m in reversed(matches):
        try:
            obj = json.loads(m.group(0))
            verdict = str(obj.get("verdict") or "ERROR").upper()
            if verdict not in ("BUY_CANDIDATE", "WATCH", "AVOID", "ERROR"):
                verdict = "WATCH"
            conf = obj.get("confidence")
            try:
                conf_i = int(float(conf))
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
        except Exception:
            continue
    return {
        "verdict": "ERROR",
        "confidence": 0,
        "reasoning": "Could not parse AI JSON response",
        "key_risk": "Parse failure — trust your raw L1/L2/L3 scores",
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
            # Keep a tiny subset of already-scraped ratios if present
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


def get_verdict(db: Database, symbol: str, force_refresh: bool = False) -> Dict[str, Any]:
    """Cached daily HF verdict for a symbol. Manual trigger only."""
    sym = symbol.strip().upper()
    today = datetime.utcnow().date().isoformat()

    token = db.get_setting("hf_token", None)
    if not token or not str(token).strip():
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

    prompt = PROMPT_TEMPLATE.format(stock_json=json.dumps(payload, default=str))
    headers = {"Authorization": f"Bearer {str(token).strip()}"}
    body = {
        "inputs": prompt,
        "parameters": {
            "max_new_tokens": 280,
            "temperature": 0.2,
            "return_full_text": False,
        },
    }

    last_err = "Timed out after 3 retries"
    for attempt in range(3):
        try:
            resp = requests.post(HF_API, headers=headers, json=body, timeout=35)
            if resp.status_code == 200:
                result = _parse_json_response(resp.json())
                result["status"] = "ok" if result.get("verdict") != "ERROR" else "error"
                result["symbol"] = sym
                result["model"] = HF_MODEL
                if result.get("verdict") != "ERROR":
                    db.save_llm_verdict(sym, today, result)
                return result
            if resp.status_code in (503, 429):
                last_err = f"API {resp.status_code} (model loading or rate limit)"
                time.sleep(5 * (attempt + 1))
                continue
            # Auth / hard errors — don't retry forever
            try:
                detail = resp.json()
                msg = detail.get("error") if isinstance(detail, dict) else resp.text[:200]
            except Exception:
                msg = resp.text[:200]
            return {
                "status": "error",
                "verdict": "ERROR",
                "confidence": 0,
                "reasoning": f"API error {resp.status_code}: {msg}",
                "key_risk": "Check HF token / model access",
                "cached": False,
                "symbol": sym,
            }
        except requests.RequestException as e:
            last_err = str(e)
            time.sleep(3)
            continue

    return {
        "status": "unavailable",
        "verdict": "ERROR",
        "confidence": 0,
        "reasoning": f"AI unavailable — {last_err}. Use your raw L1/L2/L3 score breakdown.",
        "key_risk": "External API unavailable",
        "cached": False,
        "symbol": sym,
    }


def ask_ai_verdict_json(
    symbol: str,
    db_path: Optional[str] = None,
    force_refresh: bool = False,
) -> str:
    db = Database(db_path)
    try:
        return json.dumps(get_verdict(db, symbol, force_refresh=force_refresh), default=str)
    except Exception as e:
        logger.exception("ask_ai failed")
        return json.dumps(
            {
                "status": "error",
                "verdict": "ERROR",
                "confidence": 0,
                "reasoning": str(e),
                "key_risk": "Unexpected failure",
                "cached": False,
                "symbol": (symbol or "").upper(),
            }
        )
    finally:
        db.close()


def hf_token_status_json(db_path: Optional[str] = None) -> str:
    db = Database(db_path)
    try:
        token = db.get_setting("hf_token", None)
        has = bool(token and str(token).strip())
        return json.dumps({"has_token": has, "model": HF_MODEL})
    finally:
        db.close()
