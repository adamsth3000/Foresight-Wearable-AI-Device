"""Environment-backed configuration for the project scaffold."""

from __future__ import annotations

import os
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path


@dataclass(frozen=True, slots=True)
class Settings:
    """Minimal settings model for repository initialization."""

    environment: str
    config_dir: Path
    log_config_path: Path
    log_level: str


def _repo_root() -> Path:
    return Path(__file__).resolve().parents[3]


def _resolve_path(value: str, base_dir: Path) -> Path:
    path = Path(value)
    return path if path.is_absolute() else (base_dir / path).resolve()


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """Load settings from the environment with repository defaults."""

    repo_root = _repo_root()
    config_dir = _resolve_path(os.getenv("FORESIGHT_CONFIG_DIR", "config"), repo_root)
    log_config_path = _resolve_path(
        os.getenv("FORESIGHT_LOG_CONFIG", "config/logging.yaml"),
        repo_root,
    )

    return Settings(
        environment=os.getenv("FORESIGHT_ENV", "development"),
        config_dir=config_dir,
        log_config_path=log_config_path,
        log_level=os.getenv("FORESIGHT_LOG_LEVEL", "INFO").upper(),
    )
