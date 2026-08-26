# Subsystem Boundaries

## Current Implemented State
The repository contains small `interaction`, `sessions`, optional `voice`, and optional `output` packages. The `voice` package returns text transcripts, while `output` emits cues or speech after a normal assistant response; wake handling, intent interpretation, context, and sessions remain in `interaction` and `sessions`.

## Planned Logical Subsystems

### Core Infrastructure
Shared configuration, logging, environment resolution, and repository-level runtime foundations.

### Capture And Session Workflows
Future support for live capture, structured session recording, and replay-friendly data handling across Lab and Field workflows.

### Interaction
Implemented normalized interaction, wake, intent, and pending-context behavior. Future work may add richer audio-driven interaction, developer visualization, and other interface layers.

### Intelligence
Future support for perception, intent recognition, context handling, memory, world modeling, geospatial awareness, and agent or action logic.

### Device Adapters
The optional microphone adapter is the first narrow Lab example. Future support may add development-platform sensors or later wearable hardware.

### Display And Visualization Adapters
Future support for optional visual outputs, debugging views, dashboards, or eventual wearable display integrations.

### Output Adapters
Implemented Lab cue and optional speech-output interfaces. Future adapters may target phone, Bluetooth, open-ear, wearable, bone-conduction, or haptic outputs.

## Boundary Rules
- Core intelligence should not depend directly on a specific camera, phone, GoPro, or wearable device.
- Core intelligence should not require a display implementation to function.
- Device-specific integrations should translate external inputs into stable internal representations.
- Voice adapters must transcribe one utterance without making wake, intent, context, or session decisions.
- Output adapters must consume user-facing assistant responses without making interaction or session decisions.
- Display-specific integrations should consume stable internal state rather than drive core decision-making.
- Capture and replay workflows should be designed so both live and post-processed sessions can feed the same higher-level systems over time.
