from foresight_device.core.config import get_settings


def pytest_sessionstart(session) -> None:  # type: ignore[no-untyped-def]
    get_settings.cache_clear()
