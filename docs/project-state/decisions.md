# Architectural Decisions

## 2026-08-25

### Adopt `src` Layout
- Decision: Place Python package code under `src/foresight_device`.
- Rationale: Avoid accidental imports from the repository root and keep packaging behavior explicit.

### Delay Product Functionality
- Decision: Do not implement AI, computer vision, voice, gesture, or hardware logic in the initialization phase.
- Rationale: The immediate goal is a stable development foundation and project operating model.

### Use Environment-Driven Configuration
- Decision: Resolve runtime settings from environment variables with repository defaults.
- Rationale: This keeps deployment concerns separated from source-controlled defaults.

### Start With Console Logging
- Decision: Provide centralized console logging only.
- Rationale: It is sufficient for early development and keeps the observability surface simple.

### Preserve Canonical Python Package Location
- Decision: Keep `src/foresight_device` as the canonical Python code location.
- Rationale: This preserves a clean `src` layout and avoids duplicate or competing code roots.

### Document Long-Term Architecture Before Expanding Implementation
- Decision: Expand architecture, roadmap, and project-state documentation before adding subsystem packages for future capabilities.
- Rationale: The project needs durable architectural guidance, but should avoid premature empty modules and misleading signs of implemented functionality.

### Keep Intelligence Independent From Hardware And Display
- Decision: Treat hardware and display systems as future adapters around the core assistant rather than as the architecture center.
- Rationale: This preserves portability from the Lab platform to Field workflows and later wearable hardware.

### Start Lab Implementation With Normalized Interaction And Session Models
- Decision: Implement the first Foresight Lab milestone as small normalized `interaction` and `sessions` packages.
- Rationale: This provides a hardware-independent way to represent simulated voice or gesture inputs, explicit intents, and session lifecycle transitions before adding real adapters or richer intelligence.

### Support Adventure Recording Through Explicit Confirmation
- Decision: Model the initial adventure flow as a confirmation-based session proposal rather than immediate recording.
- Rationale: This matches the planned interaction concept and creates a clean foundation for intent resolution, session state transitions, and future multimodal capture events.

### Add A Minimal Terminal Simulator Before Any Hardware Input
- Decision: Implement `Foresight Lab v0.2` as a small terminal-based CLI that routes typed text through the existing normalized interaction and session architecture.
- Rationale: This provides a human-operable development interface for manual testing while preserving hardware independence and avoiding premature microphone, GUI, mobile, or device integrations.

### Keep Wake Handling Separate From Intent Interpretation
- Decision: Handle the exact simulated wake phrase deterministically in `InteractionService`, outside the ordinary `IntentInterpreter` boundary.
- Rationale: Wake acknowledgement is a distinct control transition and should not constrain future semantic interpretation implementations.

### Introduce A Small Intent Interpreter Boundary
- Decision: Use an `IntentInterpreter` contract that returns an `IntentMatch`, with `DeterministicIntentInterpreter` as the v0.3 default.
- Rationale: Phrase rules remain localized today while future semantic interpretation can use the same small contract without coupling the interaction service to a specific implementation.

### Retain Simple Captures In Memory Only
- Decision: Normalize note and shopping-item follow-up content as transient in-memory captures.
- Rationale: This preserves a structured path for future persistence without prematurely adding a database, file storage, or repository layer.

### Keep Voice Input As An Optional Replaceable Adapter
- Decision: Add the Lab microphone path as an optional `VoiceInputAdapter` that returns one transcript at a time.
- Rationale: The existing interaction core receives a normalized `VOICE` and `MICROPHONE` interaction while microphone and speech-to-text details remain replaceable.

### Use Command-Triggered Single-Utterance Capture
- Decision: The terminal `voice` command triggers one fixed-duration microphone capture using the optional Lab adapter.
- Rationale: This permits real voice-input experiments without prematurely introducing continuous listening, ambient monitoring, or production wake-word detection.

### Keep Audio Output As Replaceable Adapters
- Decision: Define separate cue and speech-output contracts, dispatched by the CLI after a normal assistant response.
- Rationale: This allows the core to remain independent from Windows audio and keeps future speaker or haptic integrations replaceable.

### Use Windows Cues And Optional Local TTS For The Lab
- Decision: Use `winsound` for the synchronous wake cue and optional lazy `pyttsx3` SAPI5 speech output.
- Rationale: The cue has no extra dependency, while TTS remains optional and cannot prevent text-mode operation when unavailable.

### Keep Hands-Free Wake Detection Behind A Narrow Adapter
- Decision: Use an optional `WakeInputAdapter` with an openWakeWord-based Lab implementation that emits a minimal wake event for a developer-provided fixed `Hey Foresight` ONNX model.
- Rationale: The CLI can translate the event into the existing canonical wake interaction while the openWakeWord runtime, model file, and microphone frames stay outside interaction, intent, session, and output code.

### Use Sequential Microphone Ownership In Hands-Free Lab Mode
- Decision: The wake adapter releases its microphone stream before the existing fixed-duration voice adapter captures commands or follow-ups.
- Rationale: This avoids microphone contention and keeps the Lab workflow bounded rather than becoming continuous general transcription.

### Separate Wake-Model Training From Runtime Code
- Decision: Keep wake-model configuration and training stages under `training/wake`, separate from `src/foresight_device` and the main application environment.
- Rationale: The Windows CPU prototype and later Linux/NVIDIA quality training need heavier, changing dependencies without destabilizing the Foresight runtime.

### Use Explicit, Resumable Wake-Training Stages
- Decision: Record configuration hashes, package versions, input/output paths, and completion state in small per-stage manifests.
- Rationale: Generated data and quality assets are expensive, so stages must be inspectable and safely resumable without an orchestration framework.

## 2026-08-27

### Keep Phone Transport Separate From Python Capture Policy
- Decision: Treat the Android gateway as an RTSP source and normalize it into a source-neutral Python `MediaSource` descriptor.
- Rationale: Future cameras, GoPro, replay files, or wearable sources can use the same capture pipeline without Android types entering Python policy or event models.

### Use Stream-Copy Segments For The Phase 1B Rolling Buffer
- Decision: Use FFmpeg RTSP/TCP stream copy into short local fMP4 segments, then promote selected segments through FFmpeg concat stream copy.
- Rationale: This preserves the validated H.264/AAC stream while minimizing laptop CPU use. Timing is documented as segment-boundary approximate until physical validation proves the behavior.
