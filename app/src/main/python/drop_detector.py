"""Idiosyncratic drop detector."""
from __future__ import annotations

from typing import Tuple

import numpy as np


def is_idiosyncratic_drop(
    stock_ret_today: float,
    index_ret_today: float,
    beta: float,
    hist_idio_std: float,
    z_threshold: float = -1.5,
) -> Tuple[bool, float, float]:
    """
    expected = beta * index_ret
    idio = stock_ret - expected
    z = idio / hist_idio_std
    candidate if z < z_threshold
    """
    if any(np.isnan(x) for x in [stock_ret_today, index_ret_today, beta, hist_idio_std]):
        return False, float("nan"), float("nan")
    if hist_idio_std <= 1e-12:
        return False, float("nan"), float("nan")
    expected = beta * index_ret_today
    idio = stock_ret_today - expected
    z = idio / hist_idio_std
    return bool(z < z_threshold), float(idio), float(z)
