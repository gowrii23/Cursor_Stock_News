"""Pattas peer-relative scoring — sector-aware, independent of screener L1/L2."""
from __future__ import annotations

import re
import statistics
from typing import Any, Dict, List, Optional, Set, Tuple

from screener_engine import _parse_num, normalize_row

NON_FINANCIAL_PILLARS = ("pe", "div_yield", "debt_eq", "roe_3y", "fcf_yield", "growth_consistency")
FINANCIAL_PILLARS = ("pb", "div_yield", "net_npa", "roe_3y")
LOWER_IS_BETTER = frozenset({"pe", "pb", "debt_eq", "net_npa"})

FINANCIAL_MARKERS = ("net_npa", "gross_npa", "car", "nim")

# Fallback when scrape omits NPA/CAR (banks/NBFCs in Pattas universe)
FINANCIAL_SYMBOL_FALLBACK = frozenset(
    {
        "ABCAPITAL",
        "BANKBARODA",
        "CANBK",
        "CENTRALBK",
        "CUB",
        "DCB",
        "FEDERALBNK",
        "HDFCBANK",
        "ICICIBANK",
        "IDFCFIRSTB",
        "INDIANB",
        "INDUSINDBK",
        "IOB",
        "KARURVYSYA",
        "KTKBANK",
        "LTF",
        "MAHABANK",
        "MANAPPURAM",
        "MUTHOOTFIN",
        "SOUTHBANK",
        "SURYODAY",
        "TMB",
        "UCOBANK",
    }
)

# Extra raw-key aliases for company-page scrape (merged into normalize_row output)
_PATTAS_RAW_ALIASES: Dict[str, List[str]] = {
    "pb": ["price to book", "p/b", "pb ratio", "price to book value"],
    "net_npa": ["net npa %", "net npa"],
    "gross_npa": ["gross npa %", "gross npa"],
    "car": ["capital adequacy ratio", "car %", "car"],
    "nim": ["net interest margin", "nim %", "nim"],
    "book_value": ["book value"],
    "roe": ["roe %", "roe"],
    "cmp": ["cmp rs.", "current price"],
    "sales_var_3y": [
        "sales var 3yrs %",
        "sales growth 3years",
        "compounded sales growth 3 years",
        "sales growth 3y",
    ],
    "profit_var_3y": [
        "profit var 3yrs %",
        "profit growth 3years",
        "compounded profit growth 3 years",
        "profit growth 3y",
    ],
}


def _norm_key(header: str) -> str:
    return re.sub(r"\s+", " ", header.strip().lower())


def _apply_pattas_aliases(row: Dict[str, Any]) -> None:
    raw = row.get("raw") or {}
    if not isinstance(raw, dict):
        return
    lowered = {_norm_key(str(k)): v for k, v in raw.items()}
    for field, aliases in _PATTAS_RAW_ALIASES.items():
        if row.get(field) is not None:
            continue
        for alias in aliases:
            if alias in lowered:
                row[field] = _parse_num(lowered[alias])
                break


def enrich_pattas_row(row: Dict[str, Any]) -> Dict[str, Any]:
    """Normalize screener.in row and derive Pattas-specific fields."""
    out = normalize_row(row if "raw" in row else {"raw": row, **row})
    if "raw" not in out or not out["raw"]:
        out["raw"] = dict(row)
    _apply_pattas_aliases(out)

    # ROE 3Yr fallback from point-in-time ROE
    if out.get("roe_3y") is None and out.get("roe") is not None:
        out["roe_3y"] = out["roe"]
    if out.get("roe_3y") is None:
        raw = out.get("raw") or {}
        for key in ("ROE %", "ROE 3Yr %", "ROE 10Yr %"):
            if key in raw:
                out["roe_3y"] = _parse_num(raw[key])
                break

    # Net NPA from gross if only one present
    if out.get("net_npa") is None and out.get("gross_npa") is not None:
        out["net_npa"] = out["gross_npa"]

    # P/B from CMP / Book Value
    if out.get("pb") is None:
        cmp_ = out.get("cmp")
        bv = out.get("book_value")
        if cmp_ is not None and bv is not None and bv > 0:
            out["pb"] = round(cmp_ / bv, 4)

    # FCF yield = 1 / (CMP/FCF) when CMP/FCF is a positive multiple
    if out.get("fcf_yield") is None:
        cmp_fcf = out.get("cmp_fcf")
        if cmp_fcf is not None and cmp_fcf > 0:
            out["fcf_yield"] = round(1.0 / cmp_fcf, 6)

    return out


def classify_sector(row: Dict[str, Any]) -> str:
    """financial vs non-financial from scraped markers or symbol fallback."""
    if any(row.get(m) is not None for m in FINANCIAL_MARKERS):
        return "financial"
    sym = (row.get("symbol") or "").strip().upper()
    if sym in FINANCIAL_SYMBOL_FALLBACK:
        return "financial"
    return "non_financial"


def _growth_consistency_pass(row: Dict[str, Any]) -> bool:
    sales3 = row.get("sales_var_3y")
    profit3 = row.get("profit_var_3y")
    if sales3 is not None and profit3 is not None:
        return sales3 > 0 and profit3 > 0
    return False


def _group_by_ind_pe(rows: List[Dict[str, Any]]) -> Dict[Any, List[Dict[str, Any]]]:
    groups: Dict[Any, List[Dict[str, Any]]] = {}
    for row in rows:
        groups.setdefault(row.get("ind_pe"), []).append(row)
    return groups


def build_peer_groups(rows: List[Dict[str, Any]]) -> Dict[str, Any]:
    financial = [r for r in rows if r.get("sector") == "financial"]
    non_financial = [r for r in rows if r.get("sector") != "financial"]
    return {
        "financial": financial,
        "non_financial_groups": _group_by_ind_pe(non_financial),
        "all_non_financial": non_financial,
    }


def peer_median(
    peer_rows: List[Dict[str, Any]],
    field: str,
    fallback_rows: List[Dict[str, Any]],
) -> Optional[float]:
    values = [r[field] for r in peer_rows if r.get(field) is not None]
    if len(values) < 2:
        values = [r[field] for r in fallback_rows if r.get(field) is not None]
    if not values:
        return None
    return float(statistics.median(values))


def _pillars_for(row: Dict[str, Any]) -> Tuple[str, ...]:
    if row.get("sector") == "financial":
        return FINANCIAL_PILLARS
    return NON_FINANCIAL_PILLARS


def score_row(
    row: Dict[str, Any],
    peer_rows: List[Dict[str, Any]],
    basket_rows: List[Dict[str, Any]],
) -> Dict[str, Any]:
    pillars = _pillars_for(row)
    peer_size = len(peer_rows)
    used_basket_fallback = row.get("sector") != "financial" and peer_size < 2
    result: Dict[str, Any] = {
        "pillars": {},
        "peer_medians": {},
        "pattas_score": 0,
        "pillar_count": len(pillars),
        "peer_group_size": peer_size,
        "used_basket_fallback": used_basket_fallback,
        "sector": row.get("sector"),
        "missing_fields": [],
    }

    for field in pillars:
        if field == "growth_consistency":
            sales3 = row.get("sales_var_3y")
            profit3 = row.get("profit_var_3y")
            if sales3 is None or profit3 is None:
                result["pillars"][field] = None
                result["missing_fields"].append(field)
                continue
            passed = _growth_consistency_pass(row)
            result["pillars"][field] = passed
            result["peer_medians"][field] = None
            if passed:
                result["pattas_score"] += 1
            continue

        med = peer_median(peer_rows, field, basket_rows)
        val = row.get(field)
        result["peer_medians"][field] = med
        if val is None or med is None:
            result["pillars"][field] = None
            if val is None:
                result["missing_fields"].append(field)
            continue
        better = (val < med) if field in LOWER_IS_BETTER else (val > med)
        result["pillars"][field] = better
        if better:
            result["pattas_score"] += 1

    return result


def score_rows(rows: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    normalized = [enrich_pattas_row(r) for r in rows]
    for row in normalized:
        row["sector"] = classify_sector(row)

    groups = build_peer_groups(normalized)
    financial_peers = groups["financial"]
    non_fin_groups = groups["non_financial_groups"]
    all_non_fin = groups["all_non_financial"]

    for row in normalized:
        if row.get("sector") == "financial":
            peer_rows = financial_peers
            basket = financial_peers
        else:
            peer_rows = non_fin_groups.get(row.get("ind_pe"), [])
            basket = all_non_fin
        row["pattas"] = score_row(row, peer_rows, basket)

    return normalized


def find_pattas_candidates(
    all_screener_rows: List[Dict[str, Any]],
    pattas_symbols: Set[str],
    min_pillars: int = 4,
) -> List[Dict[str, Any]]:
    """Non-financial names in Screener universe beating peer medians (out of 6 pillars)."""
    scored = score_rows(all_screener_rows)
    candidates = []
    for row in scored:
        if row.get("sector") == "financial":
            continue
        sym = (row.get("symbol") or "").strip().upper()
        if not sym or sym in pattas_symbols:
            continue
        pattas = row.get("pattas") or {}
        if pattas.get("pattas_score", 0) >= min_pillars:
            row["tag"] = "PATTAS TO BE"
            candidates.append(row)

    return sorted(
        candidates,
        key=lambda r: (r.get("pattas") or {}).get("pattas_score", 0),
        reverse=True,
    )
