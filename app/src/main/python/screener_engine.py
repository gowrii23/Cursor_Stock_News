"""Pre-run-up screener: Layer 1 filters, Layer 2 scoring, Layer 3 technical overlay."""
from __future__ import annotations

import re
from typing import Any, Dict, List, Optional, Tuple

MANUAL_VERIFY_ITEMS = [
    "OCF positive in 3 of last 5 years (verify annual report)",
    "No auditor resignation in trailing 12 months",
    "No pending SEBI/RBI/ED regulatory action",
    "Related-party transactions within sector norms",
]

# Core fields required for an honest L1 pass (unknown ≠ clean)
L1_REQUIRED_FIELDS = (
    ("debt_eq", "Debt/Eq"),
    ("roce", "ROCE"),
    ("opm", "OPM"),
    ("int_coverage", "Interest coverage"),
)

# Column header → normalized field
_FIELD_ALIASES: Dict[str, List[str]] = {
    "name": ["name"],
    "symbol": ["symbol"],
    "cmp": ["cmp rs.", "cmp", "current price"],
    "pe": ["p/e", "pe"],
    "market_cap": ["mar cap rs.cr.", "market cap", "mar cap"],
    "div_yield": ["div yld %", "dividend yield"],
    "roce": ["roce %", "roce"],
    "debt_eq": ["debt / eq", "debt to equity", "debt/eq"],
    "int_coverage": ["int coverage", "interest coverage"],
    "opm": ["opm %", "opm"],
    "qtr_profit_var": ["qtr profit var %", "quarterly profit var"],
    "qtr_sales_var": ["qtr sales var %", "quarterly sales var"],
    "pledged_pct": ["pledged %", "prom. pledged %"],
    "promoter_hold": ["prom. hold. %", "promoter holding"],
    "promoter_change": ["change in prom hold %", "change in promoter holding"],
    "roe_3y": ["roe 3yr %", "roe 3y", "roe 10yr", "roe 10 yr"],
    "sales_var_3y": ["sales var 3yrs %", "sales growth 3years"],
    "profit_var_3y": ["profit var 3yrs %", "profit growth 3years"],
    "cmp_fcf": ["cmp / fcf", "price to fcf"],
    "ind_pe": ["ind pe", "industry pe"],
    "peg": ["peg ratio", "peg"],
    "volume": ["volume"],
}


def _parse_num(value: Any) -> Optional[float]:
    if value is None:
        return None
    s = str(value).strip().replace(",", "").replace("₹", "").replace("%", "")
    if not s or s in ("-", "—", "N/A", ""):
        return None
    try:
        return float(s)
    except ValueError:
        return None


def _norm_key(header: str) -> str:
    return re.sub(r"\s+", " ", header.strip().lower())


def normalize_row(raw: Dict[str, Any]) -> Dict[str, Any]:
    """Map screener.in table row to normalized fields."""
    out: Dict[str, Any] = {
        "symbol": (raw.get("symbol") or "").strip().upper(),
        "name": (raw.get("name") or raw.get("Name") or "").strip(),
        "raw": dict(raw),
    }
    if not out["symbol"] and out["name"]:
        out["symbol"] = _symbol_from_name(out["name"])

    lowered = {_norm_key(k): v for k, v in raw.items()}
    for field, aliases in _FIELD_ALIASES.items():
        for alias in aliases:
            if alias in lowered:
                val = lowered[alias]
                if field in (
                    "name",
                    "symbol",
                ):
                    out[field] = str(val).strip()
                else:
                    out[field] = _parse_num(val)
                break
    return out


def _symbol_from_name(name: str) -> str:
    return re.sub(r"[^A-Z0-9]", "", name.upper())[:20]


def layer1_filter(row: Dict[str, Any]) -> Tuple[bool, List[str], List[str]]:
    """
    Mandatory automated checks. Returns (passed, fail_reasons, manual_notes).

    Missing core fields → fail (incomplete). Unknown is not treated as clean.
    """
    fails: List[str] = []
    manual: List[str] = list(MANUAL_VERIFY_ITEMS)

    missing = [label for key, label in L1_REQUIRED_FIELDS if row.get(key) is None]
    if missing:
        fails.append("Incomplete data — missing " + ", ".join(missing))
        # Still record threshold fails for any fields that are present
        _append_threshold_fails(row, fails)
        if row.get("pledged_pct") is None:
            manual.append("Promoter pledge % — verify on screener.in (< 20%)")
        return False, fails, manual

    _append_threshold_fails(row, fails)

    pledged = row.get("pledged_pct")
    if pledged is not None:
        if pledged >= 20.0:
            fails.append(f"Promoter pledged {pledged:.1f}% ≥ 20%")
        manual = [m for m in manual if "pledge" not in m.lower()]
    else:
        manual.append("Promoter pledge % — verify on screener.in (< 20%)")

    cmp_fcf = row.get("cmp_fcf")
    if cmp_fcf is not None and cmp_fcf > 80:
        manual.append(f"CMP/FCF {cmp_fcf:.0f} — weak cash conversion, verify OCF")

    return len(fails) == 0, fails, manual


def _append_threshold_fails(row: Dict[str, Any], fails: List[str]) -> None:
    debt = row.get("debt_eq")
    if debt is not None and debt >= 1.0:
        fails.append(f"Debt/Eq {debt:.2f} ≥ 1.0")

    icr = row.get("int_coverage")
    if icr is not None and icr <= 3.0:
        fails.append(f"Interest coverage {icr:.1f} ≤ 3×")

    opm = row.get("opm")
    if opm is not None and opm <= 10.0:
        fails.append(f"OPM {opm:.1f}% ≤ 10%")

    roce = row.get("roce")
    if roce is not None and roce <= 12.0:
        fails.append(f"ROCE {roce:.1f}% ≤ 12%")


def layer2_score(row: Dict[str, Any]) -> Tuple[float, Dict[str, float], str]:
    """
    Score as % of *available* category points (missing columns don't silently cap you),
    then map to 0–100. Tiers: high ≥75, watch ≥55, else low.
    """
    earned = {
        "quality": 0.0,
        "catalyst": 0.0,
        "ownership": 0.0,
        "valuation": 0.0,
    }
    available = {
        "quality": 0.0,
        "catalyst": 0.0,
        "ownership": 0.0,
        "valuation": 0.0,
    }

    # A. Business quality (max 30 when all inputs present)
    roce = row.get("roce")
    if roce is not None:
        available["quality"] += 8
        if roce >= 20:
            earned["quality"] += 8
        elif roce >= 12:
            earned["quality"] += 4

    opm = row.get("opm")
    if opm is not None:
        available["quality"] += 7
        if opm >= 20:
            earned["quality"] += 7
        elif opm >= 10:
            earned["quality"] += 3

    sales3 = row.get("sales_var_3y")
    profit3 = row.get("profit_var_3y")
    has_3y = sales3 is not None and profit3 is not None
    if has_3y:
        available["quality"] += 8
        if sales3 > 10 and profit3 > 10:
            earned["quality"] += 8
        elif sales3 > 0 or profit3 > 0:
            earned["quality"] += 4
    elif row.get("qtr_sales_var") is not None and row.get("qtr_profit_var") is not None:
        available["quality"] += 4
        earned["quality"] += 4

    debt = row.get("debt_eq")
    if debt is not None:
        available["quality"] += 7
        if debt < 0.3:
            earned["quality"] += 7
        elif debt < 1.0:
            earned["quality"] += 4

    # B. Catalyst (max 30) — QoQ inflection; capped without multi-year support
    q_sales = row.get("qtr_sales_var")
    q_profit = row.get("qtr_profit_var")
    multi_year_ok = has_3y and sales3 is not None and profit3 is not None and (
        sales3 > 0 and profit3 > 0
    )
    if q_sales is not None and q_profit is not None:
        # Base QoQ bucket (max 20)
        available["catalyst"] += 20
        if q_sales > 15 and q_profit > 15:
            earned["catalyst"] += 20
        elif q_sales > 5 or q_profit > 5:
            earned["catalyst"] += 10
        elif q_sales > 0 and q_profit > 0:
            earned["catalyst"] += 5

        # Extra inflection bonus only if 3y growth supports the story
        if multi_year_ok:
            available["catalyst"] += 10
            if q_sales > 10 and abs(q_profit) < 30:
                earned["catalyst"] += 10
        # else: no extra 10 available — one hot quarter can't max catalyst
    elif has_3y:
        available["catalyst"] += 10
        if sales3 > 10 and profit3 > 10:
            earned["catalyst"] += 10
        elif sales3 > 0 or profit3 > 0:
            earned["catalyst"] += 5

    # C. Ownership (max 25)
    prom_chg = row.get("promoter_change")
    if prom_chg is not None:
        available["ownership"] += 10
        if prom_chg > 0.5:
            earned["ownership"] += 10
        elif prom_chg >= 0:
            earned["ownership"] += 5
    elif row.get("promoter_hold") is not None:
        available["ownership"] += 5
        earned["ownership"] += 5

    pledged = row.get("pledged_pct")
    if pledged is not None:
        available["ownership"] += 8
        if pledged < 5:
            earned["ownership"] += 8
        elif pledged < 15:
            earned["ownership"] += 4

    prom = row.get("promoter_hold")
    if prom is not None:
        available["ownership"] += 7
        if prom > 50:
            earned["ownership"] += 7

    # D. Valuation (max 15)
    pe = row.get("pe")
    ind_pe = row.get("ind_pe")
    if pe is not None and ind_pe is not None and ind_pe > 0:
        available["valuation"] += 8
        discount = (ind_pe - pe) / ind_pe
        if discount > 0.2:
            earned["valuation"] += 8
        elif discount > 0:
            earned["valuation"] += 4
    elif pe is not None:
        available["valuation"] += 4
        if pe < 25:
            earned["valuation"] += 4

    peg = row.get("peg")
    if peg is not None:
        available["valuation"] += 7
        if peg < 1.0:
            earned["valuation"] += 7
        elif peg < 1.5:
            earned["valuation"] += 4

    avail_total = sum(available.values())
    earn_total = sum(earned.values())
    # Raw points toward 100 — missing columns add 0 (no silent pass, no renorm boost).
    # Incomplete L1 already requires Debt/Eq, ROCE, OPM, ICR.
    parts = {k: round(earned[k], 1) for k in earned}
    total = round(min(100.0, earn_total), 1)

    # Retuned tiers: High needs broad strength across categories, not one hot quarter
    if total >= 70:
        tier = "high"
    elif total >= 50:
        tier = "watch"
    else:
        tier = "low"

    parts["_available"] = round(avail_total, 1)
    parts["_earned_raw"] = round(earn_total, 1)
    return total, {k: round(v, 1) for k, v in parts.items()}, tier


def layer3_technical(
    symbol: str,
    price_rows: List[Dict[str, Any]],
) -> Dict[str, Any]:
    """Technical overlay from NSE bhavcopy history (shortlist only)."""
    out: Dict[str, Any] = {
        "status": "skip",
        "signals": [],
        "score": 0,
    }
    if len(price_rows) < 30:
        out["status"] = "insufficient_data"
        out["signals"].append("Need ≥30 days price history")
        return out

    closes = [float(r["close"]) for r in price_rows]
    volumes = [float(r.get("volume") or 0) for r in price_rows]

    if len(volumes) >= 90:
        avg_vol = sum(volumes[-90:-1]) / 89
        today_vol = volumes[-1]
        if avg_vol > 0 and today_vol >= 2 * avg_vol:
            out["signals"].append("Volume ≥2× 90-day average")
            out["score"] += 25

    if len(closes) >= 60:
        window = closes[-60:]
        lo, hi = min(window), max(window)
        if hi > 0 and (hi - lo) / hi < 0.10:
            out["signals"].append("Tight 60-day base (<10% range)")
            out["score"] += 25

    if len(closes) >= 120:
        hi_52 = max(closes[-252:]) if len(closes) >= 252 else max(closes)
        if closes[-1] >= hi_52 * 0.95:
            out["signals"].append("Within 5% of range high")
            out["score"] += 25

    if len(closes) >= 21:
        ret20 = (closes[-1] - closes[-21]) / closes[-21] if closes[-21] else 0
        if ret20 > 0.05:
            out["signals"].append("20-day momentum positive")
            out["score"] += 25

    out["status"] = "ok" if out["signals"] else "neutral"
    return out


def _l3_score(row: Dict[str, Any]) -> int:
    l3 = row.get("layer3") or {}
    try:
        return int(l3.get("score") or 0)
    except Exception:
        return 0


def _apply_blueprint_tags(
    row: Dict[str, Any],
    blueprint_map: Optional[Dict[str, List[str]]],
) -> None:
    """Tag Blueprint themes after L2. Bonus is for ranking only — tier unchanged."""
    from blueprint_tagger import tags_for

    sym = (row.get("symbol") or "").strip().upper()
    tags = tags_for(sym, blueprint_map or {})
    row["blueprint_tags"] = tags
    row["blueprint_match"] = bool(tags)
    # Ranking-only nudge; does not change score_total or High/Watch/Low tier
    row["blueprint_bonus"] = 10.0 if tags else 0.0


def process_rows(
    rows: List[Dict[str, Any]],
    run_layer3: bool = True,
    db: Any = None,
    blueprint_map: Optional[Dict[str, List[str]]] = None,
) -> Dict[str, Any]:
    """Full pipeline on captured screener.in rows."""
    normalized = [normalize_row(r) for r in rows]
    passed_l1: List[Dict[str, Any]] = []
    failed_l1: List[Dict[str, Any]] = []
    incomplete = 0

    for row in normalized:
        if not row.get("symbol") and not row.get("name"):
            continue
        ok, fails, manual = layer1_filter(row)
        row["l1_passed"] = ok
        row["l1_fails"] = fails
        row["manual_notes"] = manual
        if any("Incomplete data" in f for f in fails):
            incomplete += 1
        if ok:
            score, breakdown, tier = layer2_score(row)
            row["score_total"] = score
            row["score_breakdown"] = breakdown
            row["tier"] = tier
            row["cmp"] = row.get("cmp")
            _apply_blueprint_tags(row, blueprint_map)
            passed_l1.append(row)
        else:
            row["score_total"] = 0.0
            row["tier"] = "rejected"
            _apply_blueprint_tags(row, blueprint_map)
            failed_l1.append(row)

    # Layer 3 on watch+ shortlist (score ≥ 50 matches new watch floor)
    shortlist = [r for r in passed_l1 if (r.get("score_total") or 0) >= 50]
    if run_layer3 and db is not None:
        for row in shortlist:
            sym = row.get("symbol") or ""
            hist = db.get_price_history(sym, limit=280)
            row["layer3"] = layer3_technical(sym, hist)
    else:
        for row in shortlist:
            row["layer3"] = {"status": "skip", "signals": [], "score": 0}

    # Rank: L2 score, L3 timing, then Blueprint bonus (within-tier tie-break only)
    passed_l1.sort(
        key=lambda r: (
            r.get("score_total") or 0,
            _l3_score(r),
            r.get("blueprint_bonus") or 0,
            r.get("symbol") or "",
        ),
        reverse=True,
    )
    low_count = len([r for r in passed_l1 if r.get("tier") == "low"])
    blueprint_count = len([r for r in passed_l1 if r.get("blueprint_match")])
    return {
        "total_raw": len(normalized),
        "passed_l1": len(passed_l1),
        "failed_l1": len(failed_l1),
        "incomplete": incomplete,
        "high_conviction": len([r for r in passed_l1 if r.get("tier") == "high"]),
        "watchlist": len([r for r in passed_l1 if r.get("tier") == "watch"]),
        "low_conviction": low_count,
        "blueprint_count": blueprint_count,
        "stocks": passed_l1,
        "rejected": failed_l1[:50],
        "all_rows": passed_l1 + failed_l1,
    }


def pick_top_review(rows: List[Dict[str, Any]], limit: int = 3) -> List[Dict[str, Any]]:
    """Best L1-pass names by score for pinned manual review (honest shortlist)."""
    candidates: List[Dict[str, Any]] = []
    for row in rows:
        if not row.get("l1_passed"):
            continue
        sym = (row.get("symbol") or "").strip().upper()
        if not sym:
            continue
        score = float(row.get("score_total") or 0)
        tier = row.get("tier") or "low"
        candidates.append(
            {
                "symbol": sym,
                "name": row.get("name") or sym,
                "cmp": row.get("cmp"),
                "score_total": score,
                "tier": tier,
                "l1_passed": True,
                "l3_score": _l3_score(row),
            }
        )
    candidates.sort(
        key=lambda r: (r["score_total"], r.get("l3_score") or 0, r["symbol"]),
        reverse=True,
    )
    top = candidates[:limit]
    for item in top:
        if item["tier"] == "high":
            item["review_badge"] = "70+ High conviction"
        elif item["tier"] == "watch":
            item["review_badge"] = "50–69 Watchlist"
        else:
            item["review_badge"] = "Below 50 — review manually"
        item.pop("l3_score", None)
    return top
