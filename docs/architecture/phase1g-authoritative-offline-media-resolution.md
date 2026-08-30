# Phase 1G Authoritative Offline Media Resolution

## Purpose

Offline perception, body perception, and visualization must make one media choice per promoted
event. They use `resolve_event_media(event_dir)`, a narrow adapter over the validated Phase 1G-R3.3
phone-media resolver. The adapter prefers validated `phone_media/authoritative.mp4` and otherwise
uses the legacy network capture `event.mp4`.

The adapter returns only controlled provenance:

- event ID
- private event-relative media path
- SHA-256 calculated from the selected local file
- source: `phone_local` or `network_capture`

No offline artifact persists an absolute filesystem path.

## Derived Artifact Safety

`event_perception.json` records `media.filename`, `media.sha256`, and `media.source`.
`event_body_perception.json` retains its legacy `source_media_sha256` and adds matching
`source_media` filename/SHA/source provenance. The shared `artifact_media_matches` helper compares
the resolved media with an artifact before a renderer or editor combines evidence with playback.

An existing artifact with a different media SHA, relative path, or source is stale. Consumers report
that rerunning offline processing is required; they never silently display it over selected media and
never automatically overwrite it. Older artifacts containing only a SHA remain valid when that SHA
matches the legacy resolved `event.mp4`.

Gesture artifacts remain derived from the body-artifact SHA. They do not decode media directly.

## Timestamp Semantics

Frame and observation timestamps are positions relative to the exact media selected for that
processing run. Phone-local and network event files can have different sample boundaries or encoded
timelines even for the same logical event. Foresight does not claim frame-exact equivalence between
them. Selecting another authoritative source therefore requires explicit perception/body processing
again before its artifacts may be visualized or analyzed.
