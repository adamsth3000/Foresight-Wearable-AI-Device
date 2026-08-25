from io import StringIO

from foresight_device.cli import run_cli


class KeyboardInterruptStream:
    def readline(self) -> str:
        raise KeyboardInterrupt


def test_cli_adventure_flow() -> None:
    input_stream = StringIO("Hey Foresight, I'm going on an adventure.\nYes\nexit\n")
    output_stream = StringIO()

    exit_code = run_cli(input_stream, output_stream)
    output = output_stream.getvalue()

    assert exit_code == 0
    assert "Would you like me to record this event?" in output
    assert "Adventure recording started." in output
    assert "Exiting Foresight Lab." in output


def test_cli_status_flow_shows_pending_session() -> None:
    input_stream = StringIO("Hey Foresight, I'm going on an adventure.\nstatus\nexit\n")
    output_stream = StringIO()

    exit_code = run_cli(input_stream, output_stream)
    output = output_stream.getvalue()

    assert exit_code == 0
    assert "Status: pending_confirmation" in output
    assert "Event Count: 1" in output


def test_cli_exit_command_terminates_cleanly() -> None:
    input_stream = StringIO("exit\n")
    output_stream = StringIO()

    exit_code = run_cli(input_stream, output_stream)

    assert exit_code == 0
    assert "Exiting Foresight Lab." in output_stream.getvalue()


def test_cli_handles_eof_gracefully() -> None:
    input_stream = StringIO("")
    output_stream = StringIO()

    exit_code = run_cli(input_stream, output_stream)

    assert exit_code == 0
    assert "Exiting Foresight Lab." in output_stream.getvalue()


def test_cli_handles_ctrl_c_gracefully() -> None:
    output_stream = StringIO()

    exit_code = run_cli(KeyboardInterruptStream(), output_stream)

    assert exit_code == 0
    assert "Exiting Foresight Lab." in output_stream.getvalue()
