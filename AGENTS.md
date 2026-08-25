# AGENTS.md

## Purpose
This repository contains the long-term project foundation for Foresight, a wearable AI assistant platform. The repository currently holds development scaffolding, architecture documentation, project-state tracking, and shared Python infrastructure.

## Durable Project Vision
Foresight is intended to evolve in phases while preserving a stable core architecture.

### Development Progression
1. Foresight Lab
   - Development with a computer, smartphone, and GoPro
   - Controlled experimentation with live and replayable video
   - Voice command experimentation
   - Intentional gesture simulation and recording
   - Developer visualization and debugging
2. Foresight Field
   - Real-world excursions
   - GoPro video and smartphone GPS/sensor data
   - Voice and interaction logging
   - Low-latency live interaction where appropriate
   - Structured session recording
   - Post-processing and replay after returning home
3. Foresight Intelligence
   - Perception
   - Intent recognition
   - Context
   - Memory
   - Geospatial awareness
   - World modeling
   - Agent and action capabilities
   - Personal organization and contextual assistance
4. Future Wearable
   - The same intelligence adapted to physical wearable hardware
   - Future inputs may include embedded cameras, microphones, IMU or other sensors, and optional EMG
   - Audio is the primary interaction and output model
   - A transparent waveguide display may eventually be added as an optional visualization layer

## Core Architectural Principles
- Intelligence must remain independent from specific hardware implementations.
- Intelligence must remain independent from specific display implementations.
- Hardware integrations should eventually be implemented as adapters around core intelligence.
- Display and visualization systems should eventually be implemented as adapters around core intelligence.
- The initial computer, phone, and GoPro setup is a development platform, not the final hardware architecture.
- Live capture and post-processing or replay are both first-class workflows.
- Audio and voice are primary interaction mechanisms.
- Planned gesture interaction should represent deliberate user input, and possible future EMG may help distinguish the user's gestures from unrelated movement.
- Foresight must support display-free operation even if optional visualization layers are added later.
- Phone-based visualization may be used during development to simulate future spatial interfaces without making visualization part of the core assistant.

## Current Implemented State
- Repository structure and contributor workflow foundations exist.
- Python tooling, configuration, logging, and tests exist at a scaffold level.
- Architecture, roadmap, and project-state documentation exist.
- Product functionality is not implemented yet.

## Current Phase Constraints
- Do not implement AI, computer vision, voice recognition, gesture recognition, GPS integration, GoPro integration, hardware integration, or AR functionality yet.
- Do not represent planned architecture as completed functionality.
- Do not create duplicate root-level code directories outside `src/foresight_device`.
- Do not create empty future implementation packages unless a current milestone requires them.

## Working Rules
- Preserve the existing GitHub-facing project description in `README.md`.
- Preserve the existing Python `.gitignore`.
- Prefer a `src` layout for application code.
- Keep runtime code dependency-light unless a tool is clearly justified.
- Place configuration defaults under `config/` and keep secrets out of version control.
- Add tests for infrastructure and scaffolding behavior before feature work grows.

## Collaboration Notes
- Use `src/foresight_device` as the canonical Python package root for future code.
- Keep architecture docs explicit about the difference between current implemented state, planned architecture, and future concepts.
- Record the hardware-independence and display-independence principles whenever architecture is expanded.
- Record meaningful architectural choices in `docs/project-state/decisions.md`.
- Update `docs/project-state/current-state.md` when the project phase changes.
- Keep `docs/project-state/backlog.md` focused on near-term work.
- Use `docs/project-state/next-task.md` for the immediate next recommended milestone.
- Use `docs/project-state/changelog.md` to record meaningful repository-level documentation or architecture changes.
- Treat this file as the repo-local operating guide for contributors and coding agents.
