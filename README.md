# Foresight-Wearable-AI-Device
Prototype for wearable AI device inspired by AI/AR smart glasses

## Current Status

This repository is currently an early development foundation for the long-term Foresight platform. It contains architecture documentation, contributor guidance, Python project structure, configuration, logging, testing foundations, and a minimal terminal-based Lab simulator for manual interaction testing.

The following are intentionally not implemented yet:
- AI functionality
- Computer vision
- Voice recognition
- Gesture recognition
- GPS or GoPro integration
- Hardware or AR integrations

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

This simulator currently reuses the implemented `Foresight Lab v0.1` interaction and session architecture. It does not provide speech recognition, audio output, hardware integration, or replay.

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
