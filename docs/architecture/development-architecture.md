# Development Architecture

## Purpose
This document describes the repository architecture that exists today and how it supports future Foresight development without claiming that future capabilities are already implemented.

## Current Implemented Scope
The repository includes small Lab interaction, session, and terminal-simulator milestones. The optional v0.4 voice adapter captures one microphone utterance and returns a transcript to the existing interaction core. It does not implement AI inference, computer vision, spoken output, continuous listening, gesture recognition, GPS integration, GoPro integration, device hardware integrations, or AR functionality.

## Planned Relationship To The Product
The repository is being prepared for a long-term system that will progress through:
- `Foresight Lab`
- `Foresight Field`
- `Foresight Intelligence`
- `Future Wearable`

At the moment, this repository supports that future work through documentation, shared Python scaffolding, configuration defaults, and test foundations.

## Repository Layout
- `src/foresight_device/`: application package root.
- `src/foresight_device/core/`: shared infrastructure such as configuration and logging.
- `src/foresight_device/interaction/`: normalized interactions, deterministic Lab state, and intent interpretation boundaries.
- `src/foresight_device/sessions/`: minimal adventure session lifecycle.
- `src/foresight_device/voice/`: optional microphone-to-transcript adapters; it must not own interaction or session logic.
- `src/foresight_device/output/`: optional cue and speech adapters dispatched by the CLI after core responses.
- `config/`: repository-managed default configuration assets.
- `tests/`: unit and integration test suites for scaffold behavior.
- `docs/`: architecture, contributor guidance, and project-state records.

## Package Strategy
- Use a `src` layout to prevent accidental imports from the repository root.
- Keep shared infrastructure in `core` until domain modules emerge.
- Introduce feature packages only when an implementation milestone requires them.
- Do not create duplicate root-level code directories outside `src/foresight_device`.

## Configuration Strategy
- Store checked-in defaults in `config/`.
- Use environment variables for environment-specific overrides.
- Keep secrets and machine-local configuration outside version control.

## Logging Strategy
- Centralize logging bootstrap in application infrastructure.
- Default to console logging during early development.
- Expand to file, JSON, or remote sinks only when observability requirements become concrete.

## Testing Strategy
- `tests/unit/` validates isolated infrastructure modules.
- `tests/integration/` verifies repo-level wiring and defaults.
- Add regression tests when new behavior is introduced.
- Keep tests aligned with current implemented scope rather than future intended capabilities.

## Current Repository Responsibilities
- Define durable architecture and repository guidance.
- Provide a small hardware-independent interaction and session core.
- Keep optional input adapters outside that core.
- Keep optional output adapters outside that core.
- Preserve the canonical Python package location.

## Deferred Implementation Work
The following are planned but not implemented in the current milestone:
- Lab-stage capture and replay workflows
- Field-stage session capture and post-processing workflows
- Perception and intent systems
- Context, memory, geospatial, and world-model systems
- Hardware adapters
- Display and visualization adapters
- Continuous voice monitoring, streaming audio, and production audio routing
