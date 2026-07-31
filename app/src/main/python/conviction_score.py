"""Composite conviction score (roughly 0–100)."""
from __future__ import annotations

from typing import Any, Dict


def conviction_score(
    z_drop: float,
    alpha_percentile: float,
    beta: float,
    severity_tag: str,
    blueprint_match: bool,
) -> float:
    return conviction_score_breakdown(
        z_drop, alpha_percentile, beta, severity_tag, blueprint_match
    )["total"]


def conviction_score_breakdown(
    z_drop: float,
    alpha_percentile: float,
    beta: float,
    severity_tag: str,
    blueprint_match: bool,
) -> Dict[str, Any]:
    parts: Dict[str, float] = {
        "z_drop": 0.0,
        "alpha_percentile": 0.0,
        "low_beta": 0.0,
        "news_severity": 0.0,
        "blueprint": 0.0,
    }
    try:
        parts["z_drop"] = min(abs(float(z_drop)), 3.0) * 20.0
    except Exception:
        pass
    try:
        parts["alpha_percentile"] = float(alpha_percentile) * 30.0
    except Exception:
        pass
    try:
        b = float(beta)
        parts["low_beta"] = (1.0 - min(b, 1.5) / 1.5) * 20.0
    except Exception:
        pass

    if severity_tag == "CANDIDATE":
        parts["news_severity"] = 20.0
    elif severity_tag == "UNKNOWN":
        parts["news_severity"] = 5.0
    else:
        parts["news_severity"] = -50.0

    if blueprint_match:
        parts["blueprint"] = 10.0

    total = round(sum(parts.values()), 1)
    return {"total": total, "components": {k: round(v, 1) for k, v in parts.items()}}
