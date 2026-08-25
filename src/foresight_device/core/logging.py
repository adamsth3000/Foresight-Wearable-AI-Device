"""Logging bootstrap utilities for the project scaffold."""

from __future__ import annotations

from copy import deepcopy
import logging
import logging.config
from pathlib import Path

from .config import Settings, get_settings


DEFAULT_LOGGING_CONFIG: dict[str, object] = {
    "version": 1,
    "disable_existing_loggers": False,
    "formatters": {
        "standard": {
            "format": "%(asctime)s | %(levelname)s | %(name)s | %(message)s",
        }
    },
    "handlers": {
        "console": {
            "class": "logging.StreamHandler",
            "level": "INFO",
            "formatter": "standard",
            "stream": "ext://sys.stdout",
        }
    },
    "loggers": {
        "foresight_device": {
            "level": "INFO",
            "handlers": ["console"],
            "propagate": False,
        }
    },
    "root": {
        "level": "WARNING",
        "handlers": ["console"],
    },
}


def _apply_log_level(config: dict[str, object], level: str) -> dict[str, object]:
    updated = deepcopy(config)
    handlers = updated.get("handlers", {})
    if isinstance(handlers, dict):
        for handler in handlers.values():
            if isinstance(handler, dict) and "level" in handler:
                handler["level"] = level

    root = updated.get("root")
    if isinstance(root, dict):
        root["level"] = level

    loggers = updated.get("loggers", {})
    if isinstance(loggers, dict):
        for logger_config in loggers.values():
            if isinstance(logger_config, dict):
                logger_config["level"] = level

    return updated


def load_logging_config(path: Path) -> dict[str, object]:
    """Load a repository logging config file when present.

    The project keeps the checked-in YAML asset as a human-facing source of truth.
    Runtime bootstrap remains dependency-light by using the built-in default config.
    """

    if path.exists():
        return deepcopy(DEFAULT_LOGGING_CONFIG)
    return deepcopy(DEFAULT_LOGGING_CONFIG)


def configure_logging(settings: Settings | None = None) -> None:
    """Configure Python logging for the current runtime."""

    active_settings = settings or get_settings()
    config = load_logging_config(active_settings.log_config_path)
    logging.config.dictConfig(_apply_log_level(config, active_settings.log_level))


def get_logger(name: str) -> logging.Logger:
    """Return a logger within the project namespace."""

    return logging.getLogger(name)
