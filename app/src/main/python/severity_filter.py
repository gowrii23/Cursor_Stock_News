"""Keyword severity filter — living config, not a model."""
from __future__ import annotations

from typing import Iterable, List


DEFAULT_EXCLUDE = [
    "fraud",
    "sebi ban",
    "sebi bars",
    "auditor resign",
    "forensic audit",
    "promoter pledge",
    "insolvency",
    "default on",
    "cbi probe",
    "raid",
    "license cancel",
    "going concern",
]

DEFAULT_CANDIDATE = [
    "downgrade",
    "guidance cut",
    "misses estimate",
    "profit booking",
    "sector selloff",
    "margin pressure",
    "one-off",
    "provisioning",
    "muted quarter",
    "brokerage cuts target",
]


def classify_severity(
    headline: str,
    exclude_keywords: Iterable[str] | None = None,
    candidate_keywords: Iterable[str] | None = None,
) -> str:
    h = (headline or "").lower()
    exclude = list(exclude_keywords) if exclude_keywords is not None else DEFAULT_EXCLUDE
    candidate = list(candidate_keywords) if candidate_keywords is not None else DEFAULT_CANDIDATE
    if any(k.lower() in h for k in exclude):
        return "EXCLUDE"
    if any(k.lower() in h for k in candidate):
        return "CANDIDATE"
    return "UNKNOWN"


def classify_best(
    headlines: List[str],
    exclude_keywords: Iterable[str] | None = None,
    candidate_keywords: Iterable[str] | None = None,
) -> str:
    """Prefer EXCLUDE > CANDIDATE > UNKNOWN across a set of headlines."""
    tags = [
        classify_severity(h, exclude_keywords, candidate_keywords) for h in headlines if h
    ]
    if not tags:
        return "UNKNOWN"
    if "EXCLUDE" in tags:
        return "EXCLUDE"
    if "CANDIDATE" in tags:
        return "CANDIDATE"
    return "UNKNOWN"
