"""Composite conviction score (roughly 0–100)."""
from __future__ import annotations

from typing import Optional


def conviction_score(
    z_drop: float,
    alpha_percentile: float,
    beta: float,
    severity_tag: str,
    blueprint_match: bool,
) -> float:
    score = 0.0
    try:
        score += min(abs(float(z_drop)), 3.0) * 20.0
    except Exception:
        pass
    try:
        score += float(alpha_percentile) * 30.0
    except Exception:
        pass
    try:
        b = float(beta)
        score += (1.0 - min(b, 1.5) / 1.5) * 20.0
    except Exception:
        pass

    if severity_tag == "CANDIDATE":
        score += 20.0
    elif severity_tag == "UNKNOWN":
        score += 5.0
    else:
        score -= 50.0

    if blueprint_match:
        score += 10.0

    return round(score, 1)
