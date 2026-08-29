# Changelog

## 2026-08-26
- Added a separate wake-model training skeleton with Foresight-only profiles, safe stage manifests, dependency guidance, and ignored generated artifacts; no model training or deployment was performed.

## 2026-08-25
- Initialized repository scaffolding, Python tooling, config foundations, tests, and baseline documentation.
- Expanded architecture documentation to describe the long-term Foresight vision and phase progression without claiming future functionality is implemented.
- Added durable project-state documents for backlog, next task, and changelog tracking.
- Implemented `Foresight Lab v0.1` interaction and session architecture with normalized simulated inputs, explicit intent resolution, adventure-session confirmation flow, and focused tests.
- Implemented `Foresight Lab v0.2` interactive command simulator with `python -m foresight_device`, terminal status inspection, and graceful exit handling.
- Implemented `Foresight Lab v0.3` deterministic wake handling, constrained intent-interpreter boundary, multi-step interaction context, and transient normalized note and shopping-item captures.
- Implemented `Foresight Lab v0.4` optional microphone-to-transcript adapter, `VOICE` and `MICROPHONE` normalization, and fake-adapter voice integration tests.
- Implemented `Foresight Lab v0.5` replaceable cue and speech-output contracts, Windows Lab wake cue, optional local TTS support, and fake-adapter output tests.
- Implemented `Foresight Lab v0.6` optional openWakeWord-based hands-free wake adapter, sequential wake-to-voice microphone ownership, and fake-adapter hands-free integration tests.
# 2026-08-27
- Recorded Phase 1A physical validation of the Android Galaxy S24 FE RTSP/TCP gateway, including the RootEncoder named-argument correction for `prepareVideo` bitrate and FPS.
- Implemented Phase 1B source-neutral local RTSP ingest, rolling-buffer policy, manual event promotion, SHA-256 manifests, and automated tests; physical event-window validation remains pending.
