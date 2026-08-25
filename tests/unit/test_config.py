from pathlib import Path

from foresight_device.core.config import get_settings


def test_get_settings_uses_defaults(monkeypatch) -> None:
    monkeypatch.delenv("FORESIGHT_ENV", raising=False)
    monkeypatch.delenv("FORESIGHT_CONFIG_DIR", raising=False)
    monkeypatch.delenv("FORESIGHT_LOG_CONFIG", raising=False)
    monkeypatch.delenv("FORESIGHT_LOG_LEVEL", raising=False)
    get_settings.cache_clear()

    settings = get_settings()

    assert settings.environment == "development"
    assert settings.config_dir.name == "config"
    assert settings.log_config_path == settings.config_dir / "logging.yaml"
    assert settings.log_level == "INFO"


def test_get_settings_resolves_environment_overrides(monkeypatch, tmp_path: Path) -> None:
    custom_config_dir = tmp_path / "settings"
    custom_log_path = tmp_path / "logging.yaml"
    monkeypatch.setenv("FORESIGHT_ENV", "test")
    monkeypatch.setenv("FORESIGHT_CONFIG_DIR", str(custom_config_dir))
    monkeypatch.setenv("FORESIGHT_LOG_CONFIG", str(custom_log_path))
    monkeypatch.setenv("FORESIGHT_LOG_LEVEL", "debug")
    get_settings.cache_clear()

    settings = get_settings()

    assert settings.environment == "test"
    assert settings.config_dir == custom_config_dir
    assert settings.log_config_path == custom_log_path
    assert settings.log_level == "DEBUG"
