"""Minimal terminal interface for Foresight Lab v0.2."""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import TextIO
from uuid import uuid4

from foresight_device.capture import (
    ConfiguredMediaSource,
    EventControlService,
    EventService,
    EventStateError,
    FfmpegRtspIngress,
    MediaSegment,
    MediaSourceDescriptor,
    PhoneMediaIngestService,
    RollingBuffer,
    SessionTelemetryStore,
    TelemetryReceiver,
)
from foresight_device.capture.ffmpeg_ingress import FfmpegIngressError
from foresight_device.interaction import (
    AssistantResponse,
    AssistantState,
    InteractionModality,
    InteractionService,
    InteractionSource,
    PendingInteractionContext,
    UserInteraction,
)
from foresight_device.output import (
    AudioCue,
    AudioCueOutput,
    AudioOutputUnavailableError,
    SpeechOutput,
)
from foresight_device.sessions import SessionRecord
from foresight_device.sessions.service import SessionStateError
from foresight_device.voice import (
    VoiceInputAdapter,
    VoiceInputUnavailableError,
    WakeEvent,
    WakeInputAdapter,
    WakeInputUnavailableError,
)


@dataclass(frozen=True, slots=True)
class CommandResult:
    """Result of handling a single CLI command."""

    lines: tuple[str, ...] = field(default_factory=tuple)
    should_exit: bool = False


def build_text_interaction(content: str) -> UserInteraction:
    """Convert terminal text into the normalized interaction model."""

    return UserInteraction(
        content=content,
        modality=InteractionModality.TEXT,
        source=InteractionSource.SIMULATED,
    )


def build_voice_interaction(transcript: str) -> UserInteraction:
    """Convert a microphone transcript into the normalized interaction model."""

    return UserInteraction(
        content=transcript,
        modality=InteractionModality.VOICE,
        source=InteractionSource.MICROPHONE,
    )


def build_wake_interaction(_: WakeEvent) -> UserInteraction:
    """Translate a hardware wake event into the canonical core wake interaction."""

    return build_voice_interaction("Hey Foresight")


def render_transcript(transcript: str | None) -> str:
    """Render the raw Lab voice transcript before core interaction processing."""

    if transcript is None or not transcript.strip():
        return "Transcript: <none>"
    return f'Transcript: "{transcript}"'


def render_assistant_response(response: AssistantResponse) -> tuple[str, ...]:
    """Render a normalized assistant response for terminal output."""

    lines = []
    if response.metadata.get("simulated_acknowledgement_cue") == "BEEP":
        lines.append("[BEEP]")
    lines.append(f"Foresight: {response.message}")
    cue = response.metadata.get("planned_confirmation_cue")
    if cue:
        lines.append(f"(Planned confirmation cue: {cue})")
    return tuple(lines)


def render_session_status(session: SessionRecord | None) -> tuple[str, ...]:
    """Render a compact session status summary."""

    if session is None:
        return ("No active or pending session.",)

    return (
        f"Session Type: {session.session_type.value}",
        f"Status: {session.status.value}",
        f"Session ID: {session.session_id}",
        f"Event Count: {len(session.event_log)}",
    )


def render_status(service: InteractionService) -> tuple[str, ...]:
    """Render session and interaction state for the terminal simulator."""

    return render_session_status(service.sessions.current_session) + (
        f"Assistant State: {service.assistant_state.value}",
        f"Pending Context: {service.pending_context.value}",
    )


def handle_command(
    command: str,
    service: InteractionService,
    voice_input: VoiceInputAdapter | None = None,
    cue_output: AudioCueOutput | None = None,
    speech_output: SpeechOutput | None = None,
) -> CommandResult:
    """Handle a single terminal command."""

    normalized = command.strip()
    lowered = normalized.lower()

    if lowered in {"exit", "quit"}:
        return CommandResult(lines=("Exiting Foresight Lab.",), should_exit=True)

    if lowered == "status":
        return CommandResult(lines=render_status(service))

    if lowered == "voice":
        return _handle_voice_command(service, voice_input, cue_output, speech_output)

    if not normalized:
        return CommandResult()

    return _process_interaction(
        build_text_interaction(normalized),
        service,
        cue_output,
        speech_output,
    )


def _handle_voice_command(
    service: InteractionService,
    voice_input: VoiceInputAdapter | None,
    cue_output: AudioCueOutput | None,
    speech_output: SpeechOutput | None,
) -> CommandResult:
    """Capture one Lab-only voice utterance and send its transcript to the core."""

    if voice_input is None:
        return CommandResult(
            lines=("Voice input unavailable: install the project's optional voice dependencies.",)
        )

    try:
        transcript = voice_input.listen_once()
    except VoiceInputUnavailableError as exc:
        return CommandResult(lines=(f"Voice input unavailable: {exc}",))

    if transcript is None or not transcript.strip():
        return CommandResult(
            lines=(render_transcript(transcript), "Foresight: No usable speech detected.")
        )

    result = _process_interaction(
        build_voice_interaction(transcript),
        service,
        cue_output,
        speech_output,
    )
    return CommandResult(lines=(render_transcript(transcript),) + result.lines)


def _process_interaction(
    interaction: UserInteraction,
    service: InteractionService,
    cue_output: AudioCueOutput | None = None,
    speech_output: SpeechOutput | None = None,
) -> CommandResult:
    """Run a normalized interaction through the established core flow."""

    try:
        outcome = service.process(interaction)
    except SessionStateError as exc:
        return CommandResult(lines=(f"Error: {exc}",))

    if outcome.assistant_response is None:
        return CommandResult(lines=("Foresight: No response available.",))

    response = outcome.assistant_response
    return CommandResult(
        lines=render_assistant_response(response)
        + _dispatch_audio_output(response, cue_output, speech_output)
    )


def _dispatch_audio_output(
    response: AssistantResponse,
    cue_output: AudioCueOutput | None,
    speech_output: SpeechOutput | None,
) -> tuple[str, ...]:
    """Dispatch optional output adapters without interrupting terminal behavior."""

    diagnostics: list[str] = []
    if cue_output is not None and response.metadata.get("simulated_acknowledgement_cue") == "BEEP":
        try:
            cue_output.play_cue(AudioCue.WAKE_ACKNOWLEDGEMENT)
        except AudioOutputUnavailableError as exc:
            diagnostics.append(f"Audio output unavailable: {exc}")

    if speech_output is not None:
        try:
            speech_output.speak(response.message)
        except AudioOutputUnavailableError as exc:
            diagnostics.append(f"Speech output unavailable: {exc}")

    return tuple(diagnostics)


def _run_bounded_voice_flow(
    output_stream: TextIO,
    service: InteractionService,
    voice_input: VoiceInputAdapter,
    cue_output: AudioCueOutput | None,
    speech_output: SpeechOutput | None,
) -> None:
    """Run one wake, command, and optional pending-context voice sequence."""

    _render_voice_capture(output_stream, service, voice_input, cue_output, speech_output)

    if not (
        service.assistant_state is AssistantState.LISTENING_FOR_COMMAND
        and service.pending_context is PendingInteractionContext.NONE
    ):
        return

    _render_voice_capture(output_stream, service, voice_input, cue_output, speech_output)

    if service.pending_context is not PendingInteractionContext.NONE:
        _render_voice_capture(output_stream, service, voice_input, cue_output, speech_output)


def _run_post_wake_voice_flow(
    output_stream: TextIO,
    service: InteractionService,
    voice_input: VoiceInputAdapter,
    cue_output: AudioCueOutput | None,
    speech_output: SpeechOutput | None,
) -> None:
    """Capture a command and bounded follow-up after an external wake event."""

    _render_voice_capture(output_stream, service, voice_input, cue_output, speech_output)
    if service.pending_context is not PendingInteractionContext.NONE:
        _render_voice_capture(output_stream, service, voice_input, cue_output, speech_output)
    if service.assistant_state is not AssistantState.IDLE:
        service.abandon_pending_interaction()


def _render_voice_capture(
    output_stream: TextIO,
    service: InteractionService,
    voice_input: VoiceInputAdapter,
    cue_output: AudioCueOutput | None,
    speech_output: SpeechOutput | None,
) -> None:
    """Render one explicit microphone capture without keeping it open."""

    output_stream.write("Listening for one utterance...\n")
    output_stream.flush()
    result = handle_command("voice", service, voice_input, cue_output, speech_output)
    for line in result.lines:
        output_stream.write(f"{line}\n")
    output_stream.flush()


def run_cli(
    input_stream: TextIO,
    output_stream: TextIO,
    service: InteractionService | None = None,
    voice_input: VoiceInputAdapter | None = None,
    cue_output: AudioCueOutput | None = None,
    speech_output: SpeechOutput | None = None,
) -> int:
    """Run the minimal interactive Foresight terminal loop."""

    interaction_service = service or InteractionService()
    output_stream.write("Foresight Lab v0.4\n")
    output_stream.write("Type a message, 'voice', 'status', or 'exit'.\n")

    while True:
        output_stream.write("> ")
        output_stream.flush()

        try:
            raw_command = input_stream.readline()
        except KeyboardInterrupt:
            output_stream.write("\nExiting Foresight Lab.\n")
            output_stream.flush()
            return 0

        if raw_command == "":
            output_stream.write("\nExiting Foresight Lab.\n")
            output_stream.flush()
            return 0

        if raw_command.strip().lower() == "voice" and voice_input is not None:
            _run_bounded_voice_flow(
                output_stream,
                interaction_service,
                voice_input,
                cue_output,
                speech_output,
            )
            continue

        result = handle_command(
            raw_command,
            interaction_service,
            voice_input,
            cue_output,
            speech_output,
        )
        for line in result.lines:
            output_stream.write(f"{line}\n")
        output_stream.flush()

        if result.should_exit:
            return 0


def run_hands_free_cli(
    output_stream: TextIO,
    wake_input: WakeInputAdapter,
    service: InteractionService | None = None,
    voice_input: VoiceInputAdapter | None = None,
    cue_output: AudioCueOutput | None = None,
    speech_output: SpeechOutput | None = None,
) -> int:
    """Run sequential Lab wake detection and the established bounded voice flow."""

    if voice_input is None:
        output_stream.write("Voice input unavailable: hands-free mode requires the voice extra.\n")
        output_stream.flush()
        return 1

    interaction_service = service or InteractionService()
    output_stream.write("Foresight Lab v0.6 - Hands-Free\n")
    try:
        while True:
            output_stream.write('Waiting for "Hey Foresight"...\n')
            output_stream.flush()
            try:
                wake_event = wake_input.wait_for_wake()
            except WakeInputUnavailableError as exc:
                output_stream.write(f"Wake input unavailable: {exc}\n")
                output_stream.flush()
                return 1

            result = _process_interaction(
                build_wake_interaction(wake_event),
                interaction_service,
                cue_output,
                speech_output,
            )
            for line in result.lines:
                output_stream.write(f"{line}\n")
            output_stream.flush()
            _run_post_wake_voice_flow(
                output_stream,
                interaction_service,
                voice_input,
                cue_output,
                speech_output,
            )
    except KeyboardInterrupt:
        output_stream.write("\nExiting Foresight Lab.\n")
        output_stream.flush()
        return 0
    finally:
        wake_input.close()


def run_capture_cli(
    input_stream: TextIO,
    output_stream: TextIO,
    *,
    source_uri: str,
    ffmpeg_executable: str,
    data_root: Path,
    retention_seconds: float = 60.0,
    segment_seconds: float = 2.0,
    pre_seconds: float = 30.0,
    post_seconds: float = 15.0,
    telemetry_host: str = "0.0.0.0",
    telemetry_port: int | None = None,
) -> int:
    """Run the small manual Phase 1B capture control loop."""

    started_at = datetime.now(UTC)
    capture_session_id = str(uuid4())
    source = ConfiguredMediaSource(
        MediaSourceDescriptor(
            source_id="foresight-phone",
            capture_session_id=capture_session_id,
            transport="rtsp",
            uri=source_uri,
            video_source="phone_rear_camera",
            audio_source="phone_microphone",
            session_started_utc=started_at,
            session_started_monotonic_ns=time.monotonic_ns(),
            source_session_id=f"rtsp-ingress-{capture_session_id}",
            metadata={"source_type": "rtsp"},
        )
    )
    telemetry_store = SessionTelemetryStore(data_root / "sessions", capture_session_id)
    telemetry_receiver: TelemetryReceiver | None = None
    rolling_buffer = RollingBuffer(retention_seconds)
    event_service: EventService

    def on_segment(segment: MediaSegment) -> None:
        rolling_buffer.add(segment, now=segment.ended_at_utc)
        for event in event_service.observe_segment(segment):
            output_stream.write(f"Event promoted: {event.event_id}\n")
            output_stream.write(f"Media: {event.media_path}\n")
            output_stream.flush()

    ingress = FfmpegRtspIngress(
        source,
        data_root / "buffer" / source.descriptor.capture_session_id,
        on_segment,
        executable=ffmpeg_executable,
        segment_seconds=segment_seconds,
    )
    event_service = EventService(
        source,
        rolling_buffer,
        data_root / "events",
        ingress.concatenate_to_mp4,
        pre_seconds=pre_seconds,
        post_seconds=post_seconds,
        telemetry_store=telemetry_store,
    )
    if telemetry_port is not None:
        try:
            telemetry_receiver = TelemetryReceiver(
                telemetry_store,
                telemetry_host,
                telemetry_port,
                EventControlService(event_service),
                PhoneMediaIngestService(data_root / "events"),
            )
            telemetry_receiver.start()
        except OSError as exc:
            output_stream.write(f"Telemetry unavailable: {exc}\n")

    try:
        ingress.start()
    except FfmpegIngressError as exc:
        if telemetry_receiver is not None:
            telemetry_receiver.stop()
        output_stream.write(f"Capture unavailable: {exc}\n")
        output_stream.flush()
        return 1

    output_stream.write(
        f"Foresight Phase 1C capture started. Session: {capture_session_id}. "
        "Type 'event', 'start', 'end', 'status', or 'stop'.\n"
    )
    if telemetry_receiver is not None:
        output_stream.write(
            f"Telemetry listener: {telemetry_host}:{telemetry_receiver.port}; enter "
            "http://<laptop-LAN-IP>:<port> in Android (POST /v1/bind then /v1/records).\n"
        )
        output_stream.write(
            "Event control: POST /events/start, /events/end, /events/quick; "
            "GET /events/status; POST /events/<event_id>/phone-media.\n"
        )
    output_stream.flush()
    try:
        while True:
            output_stream.write("capture> ")
            output_stream.flush()
            try:
                command = input_stream.readline()
            except KeyboardInterrupt:
                output_stream.write("\n")
                break
            if command == "":
                break
            normalized = command.strip().lower()
            if normalized == "event":
                try:
                    event_id = event_service.trigger()
                    output_stream.write(f"Manual event pending: {event_id}\n")
                except EventStateError as exc:
                    output_stream.write(f"Event rejected: {exc}\n")
            elif normalized == "start":
                try:
                    output_stream.write(f"Bounded event started: {event_service.start_bounded()}\n")
                except EventStateError as exc:
                    output_stream.write(f"Event rejected: {exc}\n")
            elif normalized == "end":
                try:
                    output_stream.write(
                        f"Bounded event finalizing: {event_service.end_bounded()}\n"
                    )
                except EventStateError as exc:
                    output_stream.write(f"Event rejected: {exc}\n")
            elif normalized == "status":
                failure = ingress.failure_message
                output_stream.write(
                    f"Segments: {len(rolling_buffer.segments)}; "
                    f"pending events: {event_service.pending_count}; "
                    f"ingress worker running: {ingress.is_running}; "
                    f"ffmpeg running: {ingress.ffmpeg_running}; "
                    f"reconnecting: {ingress.reconnecting}; "
                    f"reconnect attempt: {ingress.reconnect_attempt}.\n"
                )
                if failure is not None:
                    output_stream.write(f"{failure}\n")
            elif normalized in {"stop", "exit", "quit"}:
                break
            elif normalized:
                output_stream.write("Use 'event', 'start', 'end', 'status', or 'stop'.\n")
            output_stream.flush()
    finally:
        ingress.stop()
        aborted = event_service.abort_pending()
        if aborted:
            output_stream.write(f"Discarded {aborted} incomplete pending event(s).\n")
        cleanup_error = ingress.cleanup_temporary_media()
        if cleanup_error is not None:
            output_stream.write(f"{cleanup_error}\n")
        output_stream.write("Capture stopped.\n")
        output_stream.flush()
        if telemetry_receiver is not None:
            telemetry_receiver.stop()
    return 0
