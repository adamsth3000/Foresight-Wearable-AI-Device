from pathlib import Path

import pytest

from foresight_device.voice import OpenWakeWordInputAdapter, WakeInputUnavailableError


def test_open_wake_word_requires_a_model_path() -> None:
    adapter = OpenWakeWordInputAdapter(model_path=None)

    with pytest.raises(WakeInputUnavailableError, match="FORESIGHT_WAKE_MODEL_PATH"):
        adapter.wait_for_wake()


def test_open_wake_word_requires_an_existing_model_file(tmp_path: Path) -> None:
    adapter = OpenWakeWordInputAdapter(model_path=tmp_path / "missing.onnx")

    with pytest.raises(WakeInputUnavailableError, match="Wake model file is unavailable"):
        adapter.wait_for_wake()


def test_close_without_initialization_is_safe() -> None:
    OpenWakeWordInputAdapter(model_path="wake.onnx").close()
