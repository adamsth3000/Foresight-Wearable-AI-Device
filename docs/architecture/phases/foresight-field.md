# Foresight Field

## Phase Intent
Foresight Field extends the project from a controlled lab setting into real-world excursions and session capture.

## Planned Scope
- GoPro video and smartphone GPS or sensor data
- voice and interaction logging
- low-latency live interaction where appropriate
- structured session recording
- post-processing and replay after returning home

## Planned Experience Direction
Over time this phase may support structured real-world event capture, adventure or journey recording, and later recall or analysis of meaningful excursions.

## Architectural Role
This phase validates that Foresight can support both live use and later replay or analysis without becoming trapped in a single runtime mode.

## Current Implemented State
Phase 1A has physically validated an Android development gateway that publishes H.264/AAC media to a local MediaMTX endpoint over RTSP/TCP while the phone app is backgrounded. Phase 1B implements a source-neutral local FFmpeg ingest, rolling segment buffer, and manual event-promotion path. Phase 1B has automated coverage but remains pending a physical event-window test; GPS, sensors, GoPro, automated triggers, and replay tooling remain planned.
