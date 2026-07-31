"""Keyword severity filter — living config, not a model."""
from __future__ import annotations

from typing import Iterable, List


DEFAULT_EXCLUDE = [
    # Regulatory / legal red flags
    "fraud",
    "scam",
    "embezzle",
    "money laundering",
    "securities fraud",
    "sebi ban",
    "sebi bars",
    "sebi probe",
    "sebi penalty",
    "rbi penalty",
    "rbi ban",
    "cbi probe",
    "ed raid",
    "income tax raid",
    "raid by",
    # Audit / accounting distress
    "auditor resign",
    "auditor qualification",
    "qualified opinion",
    "forensic audit",
    "accounting irregularit",
    "material weakness",
    "going concern",
    # Capital structure / solvency
    "promoter pledge",
    "pledged shares",
    "insolvency",
    "nclt",
    "bankruptcy",
    "default on",
    "debt default",
    "loan default",
    "delisting",
    # Operational / license risk
    "license cancel",
    "licence revoked",
    "whistleblower",
]

DEFAULT_CANDIDATE = [
    # Analyst / rating actions
    "downgrade",
    "cut rating",
    "reduce target",
    "price target cut",
    "brokerage cut",
    "brokerage cuts target",
    "underperform",
    "sell rating",
    "de-rating",
    "trim estimates",
    # Guidance / earnings miss language
    "guidance cut",
    "outlook cut",
    "forecast cut",
    "weak guidance",
    "cautious outlook",
    "earnings warning",
    "misses estimate",
    "miss estimates",
    "earnings miss",
    "revenue miss",
    "miss on ebitda",
    "disappoints",
    "disappointment",
    # Market reaction / temporary pressure
    "profit booking",
    "book profits",
    "sector selloff",
    "sector weakness",
    "sector headwinds",
    "margin pressure",
    "margin squeeze",
    "operating margin",
    "one-off",
    "one time",
    "one-time",
    "provisioning",
    "higher provisions",
    "muted quarter",
    "weak quarter",
    "soft quarter",
    "revenue decline",
    "slumps",
    "plunges",
    "tumbles",
    "hits 52-week low",
    "cuts dividend",
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
