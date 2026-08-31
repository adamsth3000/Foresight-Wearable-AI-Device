# Phase 1G-R3 Local-First Field Capture

## Priority

An active phone-local recording is authoritative. RTSP, laptop control, telemetry, and sync are
optional live services and must not invalidate a healthy local camera/audio recording.

## RTSP Policy

RootEncoder `RtspStream` fans one encoder graph into both RTSP and the local MP4 recorder. Normal
`reTry()` is transport-only and may be used with bounded exponential delays of 1, 2, 4, 8, and 8
seconds. If a retry is unavailable, stalls, or would require replacing the `RtspStream` while a
recording is active, the gateway enters `DEGRADED` rather than releasing that stream.

`DEGRADED` means local recording continues, the RTSP branch is no longer retried for this capture
session, and a subsequent capture session can create a new RTSP stream. The gateway must not
create a second local recording generation or claim media continuity across a released encoder.

## Stop And Failure Boundaries

Only explicit End Capture normally stops the recorder. It stops the local MP4, computes its hash,
persists finalized metadata, then tears down RootEncoder. Genuine camera or encoder failure is
recorded as interrupted with its actual cause. Transport loss alone is never an interruption cause.

## Laptop Controls

The laptop remains authoritative for event state. When the last known state is
`recording_bounded_event`, End Event remains available during RTSP reconnecting or degraded mode.
A failed request is visible and retryable; the phone never fabricates an event end. Local-media sync
continues to use `READY -> UPLOADING -> SYNCED/FAILED`, with `FAILED` retryable.
