# Phase 1C Sensor Timing And Session Binding

Phase 1C adds phone IMU and optional location observations without coupling them to the RTSP
publisher or the Python capture policy. Raw media remains local.

## Session Identity

The laptop capture runtime creates the canonical `capture_session_id`. It identifies the complete
logical Foresight session and may include independent camera, microphone, IMU, and location
sources. Each device keeps a distinct `source_session_id`; the Galaxy gateway creates one when its
foreground capture service starts. `source_id` identifies the physical source domain, currently
`galaxy_s24_fe`.

The phone may start before the laptop. It collects timestamped sensor records in a bounded queue.
When its HTTP telemetry sender reaches the laptop, `POST /v1/bind` supplies source identity,
source-session identity, metadata, and a clock anchor. The receiver binds that source session to
its active canonical capture session and returns the canonical ID. Later `POST /v1/records` batches
retain all three IDs. A reconnect requires the receiver to recognize the same explicit source
binding; no old source session is silently attached to a new logical capture session.

## Timing

Android `SystemClock.elapsedRealtimeNanos()` is the primary phone observation clock. A source clock
anchor stores both an elapsed-realtime value and UTC at the same session boundary. The laptop maps
each observation to `observed_at_utc` from that anchor, while retaining its original elapsed value.
Network arrival and binding times are provenance only and never substitute for acquisition time.

Accelerometer values are Android device coordinates (`x` right, `y` up, `z` out of screen) in
`m_s2`; gyroscope values use the same axes in `rad_s`. Location records retain provider, latitude,
longitude, accuracy, and optional altitude, speed, and bearing with `Location.elapsedRealtimeNanos`.

Camera timing records include the Camera2 `SENSOR_INFO_TIMESTAMP_SOURCE`. RootEncoder 2.8.0's
`FrameCapturedCallback` reports Camera2 exposure-start timestamps. A `realtime` source is comparable
with elapsed realtime; Phase 1C deliberately does not claim direct encoded RTP/PTS association.

## Persistence And Events

The receiver writes durable session telemetry to
`data/capture/sessions/<capture_session_id>/timing.json` and `sensors.jsonl`. Event promotion copies
records whose mapped `observed_at_utc` falls inside the actual promoted media interval into
`data/capture/events/<event_id>/sensors.jsonl`, then records its count and selection semantics in the
event manifest. Temporary rolling-media cleanup never removes durable session telemetry.
