# Data Model

## Current Implemented State
No production data model is implemented yet. This document describes planned data concerns only.

## Planned Data Categories
- sessions
- live events
- replay events
- media references
- timestamps and timelines
- interaction records
- contextual state
- memory artifacts
- geospatial artifacts
- world-model artifacts

## Early Architectural Needs
The system will eventually need representations that can support both live and replayed workflows across Lab and Field stages.

Examples of future data concerns include:
- synchronized capture sessions
- structured event logs
- annotations and developer notes
- replayable timelines
- state snapshots for debugging or post-processing

## Boundary Reminder
This document does not define implemented schemas yet. It only establishes that data structures should eventually be:
- portable across workflows
- compatible with replay and post-processing
- independent from a single hardware vendor
