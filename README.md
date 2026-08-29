# Foresight-Wearable-AI-Device
Prototype for wearable AI device inspired by AI/AR smart glasses

## Current Status

This repository is currently an early development foundation for the long-term Foresight platform. It contains architecture documentation, contributor guidance, Python project structure, configuration, logging, testing foundations, and a minimal terminal-based Lab simulator for manual interaction testing.

The following are intentionally not implemented yet:
- AI functionality
- Computer vision
- Gesture recognition
- GPS or GoPro integration
- AI, perception, and AR integrations

## Long-Term Vision

Foresight is a long-term wearable AI assistant platform that is expected to progress through four major stages:

1. `Foresight Lab`
   Development with a computer, smartphone, and GoPro for controlled experimentation, replayable media, voice and gesture experimentation, and developer visualization.
2. `Foresight Field`
   Real-world excursions with structured capture, low-latency interaction where appropriate, and post-processing or replay after returning home.
3. `Foresight Intelligence`
   Growth of perception, intent recognition, context, memory, geospatial awareness, world modeling, and agent capabilities.
4. `Future Wearable`
   Adaptation of the same intelligence to wearable hardware, with audio-first interaction and optional future visualization layers such as a transparent waveguide display.

## Architectural Principles

- Core intelligence should remain independent from specific hardware.
- Core intelligence should remain independent from specific display implementations.
- Device integrations should eventually be adapters, not the center of the system architecture.
- Visualization should remain optional rather than required for core assistant behavior.
- Live capture and post-processing or replay are both first-class workflows.

## Repository Structure

- `AGENTS.md`: repository operating guide for contributors and coding agents
- `config/`: checked-in configuration assets
- `docs/`: architecture, setup guides, and project-state records
- `src/foresight_device/`: Python package scaffold
- `tests/`: unit and integration test foundations

## Development Setup

1. Create a virtual environment:
   `python -m venv .venv`
2. Activate it in PowerShell:
   `.venv\Scripts\Activate.ps1`
3. Install the project with development tools:
   `python -m pip install -e .[dev]`
4. Optionally create a local environment file:
   `Copy-Item .env.example .env`

## Development Commands

- Run tests: `pytest`
- Run linting: `ruff check .`
- Run type checks: `mypy src`

## Run The Lab Simulator

Start the minimal terminal simulator with:

`python -m foresight_device`

Supported commands:
- normal text input
- `status`
- `exit`
- `quit`

This simulator currently includes `Foresight Lab v0.6`: deterministic interaction, session, context, voice, cue, and optional hands-free wake experiments. Type `Hey Foresight` to receive the `[BEEP]` acknowledgement, then try an adventure command, `Take a note`, or `Add something to my shopping list`.

To use optional Lab voice input, install it in a compatible environment. Python 3.11 is the recommended initial voice runtime because the current Python 3.14 environment may not support all native speech-to-text dependencies reliably:

`py -3.11 -m venv .venv-voice`

`.venv-voice\Scripts\Activate.ps1`

`python -m pip install -e ".[dev,voice,audio,wake]"`

Start the Lab with `python -m foresight_device`, then type `voice` to capture one fixed-duration utterance. The first use may provision the local `base.en` model. The `voice` command is a Lab development control, not part of Foresight's interaction vocabulary.

The Lab now plays a local Windows wake tone in addition to the terminal `[BEEP]` marker. Optional TTS uses `pyttsx3` only when `FORESIGHT_LAB_SPEAK_RESPONSES=1` is set before starting the Lab. If TTS is unavailable, terminal output and the wake cue continue where supported.

For optional hands-free Lab wake listening, train or otherwise provide a custom openWakeWord `Hey Foresight` ONNX model, set `FORESIGHT_WAKE_MODEL_PATH` to that local file, then run:

`python -m foresight_device --hands-free`

The wake adapter owns the microphone only while waiting for the fixed phrase. After detection, it releases the microphone before the existing one-utterance voice adapter captures the command and any required follow-up. Press `Ctrl+C` to exit hands-free mode. The developer-provided `.onnx` model is not committed. `WakeInputAdapter` is the stable Foresight boundary, so the Lab implementation and model can be replaced later without changing the interaction core.

Use the upstream openWakeWord custom-model training notebook or its detailed training workflow to create a model specifically for `Hey Foresight`; do not download an arbitrary community model. The resulting local ONNX model must be compatible with openWakeWord's `Model(wakeword_models=[...])` interface. Training and model evaluation are separate Lab work and are not implemented in this repository.

Notes and shopping items are normalized transient in-memory captures only; they are not persisted or connected to a real list system. The simulator does not provide continuous full-speech transcription, production wake-word monitoring, streaming audio, phone or wearable audio integration, AI interpretation, or replay.

## Wake-Model Training

`training/wake/` is a separate Windows/VS Code training workspace for a future developer-provided `hey_foresight` ONNX wake model. It uses its own dependencies, profiles, and resumable manifests; no model is trained or deployed in this repository baseline. See `training/wake/README.md` before creating the separate training environment.

## Phase 1 Capture

Phase 1A has physically validated the native Android gateway: the Galaxy S24 FE publishes rear-camera H.264 (1280x720) and AAC (44.1 kHz stereo) to MediaMTX over RTSP/TCP, including while the app is backgrounded or the screen is off. RootEncoder `prepareVideo` uses named arguments because its parameter order is `width`, `height`, `bitrate`, then `fps`.

Phase 1B implements a local, source-neutral Python RTSP ingest and manual-event pipeline, but has **not** yet passed a physical event-window test. Install FFmpeg separately, start the Android gateway and MediaMTX, then run:

```powershell
python -m foresight_device --capture --source-uri rtsp://LAPTOP_LAN_IP:8555/foresight-phone
```

Wait at least 30 seconds, type `event`, wait at least 15 seconds, then type `stop`. The MVP stream-copies two-second fMP4 segments into `data/capture/buffer/` and promotes a stream-copy-concatenated `event.mp4` plus a SHA-256 manifest under `data/capture/events/<event_id>/`. Segment boundaries provide approximate rather than frame-accurate event timing. See `config/capture.yaml` for defaults; the CLI URI and FFmpeg path remain machine-configurable.

## Documentation

- Architecture overview: `docs/architecture/overview.md`
- Architecture principles: `docs/architecture/principles.md`
- Development architecture: `docs/architecture/development-architecture.md`
- Subsystem boundaries: `docs/architecture/subsystem-boundaries.md`
- Hardware abstraction: `docs/architecture/hardware-abstraction.md`
- Display abstraction: `docs/architecture/display-abstraction.md`
- Data model: `docs/architecture/data-model.md`
- Foresight Lab: `docs/architecture/phases/foresight-lab.md`
- Foresight Field: `docs/architecture/phases/foresight-field.md`
- Foresight Intelligence: `docs/architecture/phases/foresight-intelligence.md`
- Future Wearable: `docs/architecture/phases/future-wearable.md`
- Getting started: `docs/development/getting-started.md`
- Roadmap: `docs/development/roadmap.md`
- Testing strategy: `docs/development/testing-strategy.md`
- Current state: `docs/project-state/current-state.md`
- Decisions: `docs/project-state/decisions.md`
- Backlog: `docs/project-state/backlog.md`
- Next task: `docs/project-state/next-task.md`
- Changelog: `docs/project-state/changelog.md`
