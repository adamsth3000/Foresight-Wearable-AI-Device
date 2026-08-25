import logging

from foresight_device.core.config import Settings
from foresight_device.core.logging import configure_logging


def test_configure_logging_applies_requested_level(tmp_path) -> None:
    settings = Settings(
        environment="test",
        config_dir=tmp_path,
        log_config_path=tmp_path / "logging.yaml",
        log_level="DEBUG",
    )

    configure_logging(settings)

    logger = logging.getLogger("foresight_device")
    assert logger.getEffectiveLevel() == logging.DEBUG
