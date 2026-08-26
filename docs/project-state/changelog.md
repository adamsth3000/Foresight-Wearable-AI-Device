# Changelog

## 2026-08-25
- Initialized repository scaffolding, Python tooling, config foundations, tests, and baseline documentation.
- Expanded architecture documentation to describe the long-term Foresight vision and phase progression without claiming future functionality is implemented.
- Added durable project-state documents for backlog, next task, and changelog tracking.
- Implemented `Foresight Lab v0.1` interaction and session architecture with normalized simulated inputs, explicit intent resolution, adventure-session confirmation flow, and focused tests.
- Implemented `Foresight Lab v0.2` interactive command simulator with `python -m foresight_device`, terminal status inspection, and graceful exit handling.
- Implemented `Foresight Lab v0.3` deterministic wake handling, constrained intent-interpreter boundary, multi-step interaction context, and transient normalized note and shopping-item captures.
- Implemented `Foresight Lab v0.4` optional microphone-to-transcript adapter, `VOICE` and `MICROPHONE` normalization, and fake-adapter voice integration tests.
- Implemented `Foresight Lab v0.5` replaceable cue and speech-output contracts, Windows Lab wake cue, optional local TTS support, and fake-adapter output tests.
