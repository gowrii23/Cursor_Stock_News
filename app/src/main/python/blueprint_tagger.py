"""BSE Blueprint theme tagging from static JSON mapping."""
from __future__ import annotations

import json
import os
from typing import Dict, List, Optional


def load_blueprint_map(path: Optional[str] = None) -> Dict[str, List[str]]:
    if path and os.path.exists(path):
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        return {str(k).upper(): list(v) for k, v in data.items()}
    return {}


def tags_for(ticker: str, mapping: Dict[str, List[str]]) -> List[str]:
    return list(mapping.get(ticker.upper(), []))
