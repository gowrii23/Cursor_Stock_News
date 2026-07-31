"""Rolling beta / alpha via numpy OLS (CAPM)."""
from __future__ import annotations

from typing import Optional, Tuple

import numpy as np
import pandas as pd


def ols_alpha_beta(
    stock_returns: pd.Series,
    index_returns: pd.Series,
    rf_daily: float = 0.0,
) -> Tuple[float, float]:
    """
    Returns (alpha_daily, beta) from OLS:
      (Rs - rf) = alpha + beta * (Rm - rf) + e
    """
    y = (stock_returns - rf_daily).astype(float).values
    x = (index_returns - rf_daily).astype(float).values
    if len(y) < 30:
        return float("nan"), float("nan")
    X = np.column_stack([np.ones(len(x)), x])
    try:
        coeffs, _, _, _ = np.linalg.lstsq(X, y, rcond=None)
        alpha_daily, beta = float(coeffs[0]), float(coeffs[1])
        return alpha_daily, beta
    except Exception:
        return float("nan"), float("nan")


def compute_beta_alpha(
    stock_returns: pd.Series,
    index_returns: pd.Series,
    rf_daily: float = 0.0,
) -> Tuple[float, float]:
    """Returns (beta, alpha_annualized)."""
    alpha_daily, beta = ols_alpha_beta(stock_returns, index_returns, rf_daily)
    if np.isnan(alpha_daily) or np.isnan(beta):
        return float("nan"), float("nan")
    return beta, alpha_daily * 252.0


def rolling_windows(
    stock_returns: pd.Series,
    index_returns: pd.Series,
    window_1y: int = 252,
    window_3y: int = 756,
) -> Tuple[float, float, float]:
    """
    Compute beta_1y, alpha_1y (ann), alpha_3y (ann) on trailing windows.
    """
    n = len(stock_returns)
    if n < 60:
        return float("nan"), float("nan"), float("nan")

    s1 = stock_returns.iloc[-min(window_1y, n) :]
    i1 = index_returns.iloc[-min(window_1y, n) :]
    beta_1y, alpha_1y = compute_beta_alpha(s1, i1)

    s3 = stock_returns.iloc[-min(window_3y, n) :]
    i3 = index_returns.iloc[-min(window_3y, n) :]
    _, alpha_3y = compute_beta_alpha(s3, i3)
    return beta_1y, alpha_1y, alpha_3y


def residual_std(
    stock_returns: pd.Series,
    index_returns: pd.Series,
    beta: float,
    window: int = 252,
) -> float:
    """Historical idiosyncratic return std using fixed beta."""
    if np.isnan(beta) or len(stock_returns) < 30:
        return float("nan")
    s = stock_returns.iloc[-min(window, len(stock_returns)) :]
    i = index_returns.iloc[-min(window, len(index_returns)) :]
    idio = s - beta * i
    return float(idio.std(ddof=1)) if len(idio) > 5 else float("nan")


def percentile_rank(values: list, value: float) -> float:
    arr = np.array([v for v in values if v is not None and not np.isnan(v)], dtype=float)
    if len(arr) == 0 or value is None or np.isnan(value):
        return 0.0
    return float((arr <= value).sum() / len(arr))
