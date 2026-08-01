"""Headless screener.in company-page ratio capture for Pattas scan."""
from __future__ import annotations

import re
from typing import Dict, List, Optional, Tuple

import requests

RATIO_URL = "https://www.screener.in/company/{symbol}/consolidated/"
_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    ),
}

# Map screener.in top-panel labels → keys understood by screener_engine.normalize_row
_LABEL_TO_FIELD = {
    "Stock P/E": "P/E",
    "Dividend Yield": "Div Yld %",
    "Current Price": "CMP Rs.",
    "ROCE": "ROCE %",
    "ROE": "ROE %",
    "Market Cap": "Mar Cap Rs.Cr.",
}


def _parse_top_panel(html: str) -> Dict[str, str]:
    pairs = re.findall(
        r'<span class="name">\s*([^<]+?)\s*</span>.*?<span class="number">([^<]+)</span>',
        html,
        re.S,
    )
    out: Dict[str, str] = {}
    for name, value in pairs:
        out[name.strip()] = value.strip()
    return out


def _parse_roe_3y(html: str) -> Optional[str]:
    m = re.search(r"3 Years:</td>\s*<td>([^<]+)</td>", html)
    return m.group(1).strip() if m else None


def _parse_debt_eq(html: str) -> Optional[str]:
    """Best-effort Debt/Eq from ratios table (latest column)."""
    ratios = re.search(r'id="ratios".*?</section>', html, re.S)
    if not ratios:
        return None
    for row in re.findall(r"<tr[^>]*>(.*?)</tr>", ratios.group(0), re.S):
        label_m = re.search(r'<td class="text[^"]*">([^<]+)</td>', row)
        if not label_m:
            continue
        label = label_m.group(1).strip().lower()
        if "debt" in label and "equity" in label:
            cells = re.findall(r"<td[^>]*>(.*?)</td>", row, re.S)
            if len(cells) < 2:
                continue
            last = re.sub(r"<[^>]+>", "", cells[-1]).strip()
            return last if last and last not in ("-", "—") else None
    return None


def fetch_company_ratios(symbol: str) -> Optional[Dict[str, str]]:
    """Best-effort headless scrape. Returns None on ANY doubt — caller falls back to WebView."""
    try:
        resp = requests.get(
            RATIO_URL.format(symbol=symbol.upper()),
            headers=_HEADERS,
            timeout=15,
        )
        if resp.status_code != 200:
            return None
        html = resp.text
        panel = _parse_top_panel(html)
        if len(panel) < 4:
            return None

        row: Dict[str, str] = {"symbol": symbol.upper()}
        for src, dst in _LABEL_TO_FIELD.items():
            if src in panel:
                row[dst] = panel[src]

        roe3 = _parse_roe_3y(html)
        if roe3:
            row["ROE 3Yr %"] = roe3

        debt = _parse_debt_eq(html)
        if debt:
            row["Debt / Eq"] = debt

        if "P/E" not in row:
            return None
        return row
    except requests.RequestException:
        return None


def fetch_many(symbols: List[str]) -> Tuple[List[Dict[str, str]], List[str]]:
    """Returns (rows_captured, symbols_that_need_webview_fallback)."""
    rows: List[Dict[str, str]] = []
    failed: List[str] = []
    for sym in symbols:
        row = fetch_company_ratios(sym)
        if row:
            rows.append(row)
        else:
            failed.append(sym.upper())
    return rows, failed
