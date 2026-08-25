# Development Architecture

## Purpose
This document describes the repository architecture that exists today and how it supports future Foresight development without claiming that future capabilities are already implemented.

## Current Implemented Scope
The repository is intentionally limited to development infrastructure. It does not yet implement AI inference, computer vision, voice recognition, gesture recognition, GPS integration, GoPro integration, device hardware integrations, or AR functionality.

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
- Preserve the canonical Python package location.
- Provide enough development structure for future milestones to land cleanly.

## Deferred Implementation Work
The following are planned but not implemented in the current milestone:
- Lab-stage capture and replay workflows
- Field-stage session capture and post-processing workflows
- Perception and intent systems
- Context, memory, geospatial, and world-model systems
- Hardware adapters
- Display and visualization adapters
