# Gateway Operating Modes V1

Gateway has an explicit user-selected operating policy persisted in Android preferences. It defaults
to `LAB` for existing installations. Network reachability, RTSP state, Tailscale, MediaMTX, and
laptop availability never select or change the mode.

## LAB

Lab starts local recording and the configured RTSP transport together. It retains laptop-authoritative
event control, live preview, best-effort telemetry, and R3 sync. The local-first recording protection
still applies when RTSP fails.

## FIELD V1

Field starts the same local Camera2/H.264/AAC recording without requiring an RTSP endpoint or laptop.
When no endpoint is configured, RTSP state is `OFFLINE`; the local recording remains active and End
Capture finalizes it normally. If an endpoint is configured, RTSP remains opportunistic and can enter
reconnecting or degraded state without affecting local capture.

Field V1 did not create phone-authoritative events. Start Event and End Event continued to use the
laptop-authoritative protocol; unavailable control was reported and retryable but did not affect local
capture. Sync was mode-independent and remained governed solely by durable local event-media state.

## FIELD V2

Field V2 makes local event capture a phone-authoritative capability. With an active local recording,
Start Event creates a UUID on the phone and persists its UTC receipt time, `elapsedRealtime` basis,
and recording-relative monotonic offset. End Event persists the matching end boundary and leaves the
event `READY` for normal R3.2 extraction once the recording finalizes. End Capture closes an active
FIELD event with termination reason `CAPTURE_STOP` before finalization.

No laptop API is called by FIELD Start or End. Laptop control remains authoritative only in LAB mode.
The retained event can later use the existing explicit R3.3 sync button. Its upload identifies the
event as `phone_field`; the laptop creates a phone-authoritative manifest without fake RTSP/network
capture provenance. The app does not claim frame-exact alignment: receipt-to-media mapping is based
on Android monotonic elapsed time and has normal UI scheduling and encoder/muxer boundary uncertainty.
