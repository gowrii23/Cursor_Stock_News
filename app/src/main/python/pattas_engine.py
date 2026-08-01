"""Pattas peer-relative scoring — independent of screener tier/L1/L2 logic."""
from __future__ import annotations

import statistics
from typing import Any, Dict, List, Optional, Set

from screener_engine import normalize_row

PILLARS = ("pe", "div_yield", "debt_eq", "roe_3y")
LOWER_IS_BETTER = frozenset({"pe", "debt_eq"})


def build_peer_groups(rows: List[Dict[str, Any]]) -> Dict[Any, List[Dict[str, Any]]]:
    """Group normalized rows by ind_pe (screener.in sector bucket)."""
    groups: Dict[Any, List[Dict[str, Any]]] = {}
    for row in rows:
        key = row.get("ind_pe")
        groups.setdefault(key, []).append(row)
    return groups


def peer_median(
    peer_rows: List[Dict[str, Any]],
    field: str,
    fallback_rows: List[Dict[str, Any]],
) -> Optional[float]:
    values = [r[field] for r in peer_rows if r.get(field) is not None]
    used_fallback = False
    if len(values) < 2:
        values = [r[field] for r in fallback_rows if r.get(field) is not None]
        used_fallback = True
    if not values:
        return None
    med = float(statistics.median(values))
    return med


def score_row(
    row: Dict[str, Any],
    peer_rows: List[Dict[str, Any]],
    basket_rows: List[Dict[str, Any]],
) -> Dict[str, Any]:
    peer_size = len(peer_rows)
    used_basket_fallback = peer_size < 2
    result: Dict[str, Any] = {
        "pillars": {},
        "peer_medians": {},
        "pattas_score": 0,
        "peer_group_size": peer_size,
        "used_basket_fallback": used_basket_fallback,
    }
    for field in PILLARS:
        med = peer_median(peer_rows, field, basket_rows)
        val = row.get(field)
        result["peer_medians"][field] = med
        if val is None or med is None:
            result["pillars"][field] = None
            continue
        better = (val < med) if field in LOWER_IS_BETTER else (val > med)
        result["pillars"][field] = better
        if better:
            result["pattas_score"] += 1
    return result


def score_rows(rows: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    normalized = [normalize_row(r) for r in rows]
    groups = build_peer_groups(normalized)
    for row in normalized:
        peer_rows = groups.get(row.get("ind_pe"), [])
        row["pattas"] = score_row(row, peer_rows, normalized)
    return normalized


def find_pattas_candidates(
    all_screener_rows: List[Dict[str, Any]],
    pattas_symbols: Set[str],
    min_pillars: int = 3,
) -> List[Dict[str, Any]]:
    """Flag non-Pattas universe names that beat peer medians on enough pillars."""
    scored = score_rows(all_screener_rows)
    candidates = [
        row
        for row in scored
        if row.get("symbol") not in pattas_symbols
        and (row.get("pattas") or {}).get("pattas_score", 0) >= min_pillars
    ]
    for candidate in candidates:
        candidate["tag"] = "PATTAS TO BE"
    return sorted(
        candidates,
        key=lambda r: (r.get("pattas") or {}).get("pattas_score", 0),
        reverse=True,
    )
