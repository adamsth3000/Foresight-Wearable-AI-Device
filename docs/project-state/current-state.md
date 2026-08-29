# Current State

## Snapshot
- Date: August 27, 2026
- Phase: Phase 1B local capture and manual event pipeline
- Status: Implemented and awaiting physical RTSP event-window validation

## What Exists
- Repository license and Git metadata
- Preserved Python `.gitignore`
- Preserved GitHub-facing README header and project description
- Durable project documentation structure
- Python package, configuration, logging, and testing foundations
- Long-term architecture documentation for Lab, Field, Intelligence, and Future Wearable phases
- A minimal hardware-independent interaction architecture for normalized simulated inputs
- A minimal session lifecycle for adventure recording confirmation and state transitions
- A minimal terminal interface for manually sending text commands through the interaction and session architecture
- Deterministic wake acknowledgement for the exact simulated `Hey Foresight` phrase
- A small interpreter boundary with deterministic example phrase mappings for constrained intents
- In-memory multi-step note and shopping-item capture with no persistence
- Manual status inspection for current session, assistant state, and pending interaction context
- An optional microphone-to-transcript adapter that feeds `VOICE` and `MICROPHONE` interactions into the existing core
- A bounded voice flow that captures wake, command, and one pending-context reply before returning to the CLI
- Replaceable output contracts for a Windows wake cue and optional local text-to-speech
- An optional openWakeWord-based hands-free wake adapter with a fixed developer-provided `Hey Foresight` ONNX model
- Sequential wake-listening and bounded voice capture so only one adapter owns the microphone at a time
- A separate, versioned wake-training skeleton with local prototype and cloud-quality profiles, stage manifests, and ignored generated artifacts
- Phase 1A native Android gateway physically validated with Galaxy S24 FE rear-camera H.264/AAC publishing to MediaMTX over RTSP/TCP, including foreground-service background operation
- Phase 1B source-neutral Python media-source records, FFmpeg stream-copy RTSP ingress, two-second rolling fMP4 segments, manual event promotion, hashes, and manifests

## What Does Not Exist Yet
- Computer vision pipelines
- Continuous full-speech transcription, production wake-word monitoring, streaming/background audio, or production audio routing
- Simulated or real gesture recognition
- GPS or GoPro integrations
- Hardware control, wearable integrations, or AR functionality
- Physical Phase 1B event-window validation, frame-accurate clipping, replay tooling, or production field capture pipelines
- AI models, NLP, perception, contextual intelligence, or hardware adapters
- A trained, evaluated, or deployed `hey_foresight.onnx` model

## Current Truth
The repository now includes Lab interaction components, a physically validated Android RTSP development gateway, and a local source-neutral capture implementation. The capture path remains local by default and is not yet physically validated for manual pre/post-event media promotion. No AI, CV, sensor, cloud, or production wearable functionality is implemented.

## Next Transition
Run the Phase 1B physical RTSP event test before expanding capture behavior.
