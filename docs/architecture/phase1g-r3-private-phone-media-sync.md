# Phase 1G-R3.3 Private Phone Media Sync

## Scope

R3.3 transfers a finalized Android event-local MP4 to the laptop over the existing private
capture-control listener. It does not change continuous capture, RTSP, local extraction, or the
existing laptop network capture at `event.mp4`.

## Endpoint And Wire Contract

The Android client sends:

```text
POST /events/<event_id>/phone-media
Content-Type: application/octet-stream
Content-Length: <exact media byte length>
X-Foresight-Source-Session-Id: <Android source session>
X-Foresight-Recording-Id: <Android recording ID>
X-Foresight-Media-Length: <exact media byte length>
X-Foresight-Media-Sha256: <lowercase SHA-256>
X-Foresight-Observed-Start-Utc: <ISO-8601 timestamp>
X-Foresight-Observed-End-Utc: <ISO-8601 timestamp>
X-Foresight-Start-Offset-Ms: <event-local media offset>
X-Foresight-End-Offset-Ms: <event-local media offset>
X-Foresight-Output-Duration-Ms: <extracted duration>
X-Foresight-Audio-Present: true|false

<streamed MP4 bytes>
```

The client uses fixed-length streaming and never loads the MP4 into memory. It permits only
private IPv4/IPv6 addresses or `.ts.net` Tailscale hostnames, and only `http` or `https` base
URLs without a path suffix. This is private-network scope, not Internet-facing authorization.

## Laptop Staging And Verification

For a valid existing event, the receiver writes only below:

```text
data/capture/events/<event_id>/phone_media/
  incoming.partial
  authoritative.mp4
  metadata.json
```

It streams exactly the declared byte count while hashing, checks length and SHA-256, invokes
`ffprobe`, requires video and positive duration, checks declared audio when applicable, and
compares duration with extraction metadata using a bounded tolerance. Only then is the partial
atomically renamed to `authoritative.mp4` and `metadata.json` plus the existing event manifest
are atomically updated. `event.mp4` remains unchanged.

An identical validated `(event_id, SHA-256)` upload returns idempotent success. A different SHA
for the same event returns conflict and leaves the accepted phone media untouched.

## Provenance And Resolution

The event manifest keeps the legacy `media` entry and gains backward-compatible
`network_capture`, `phone_local`, and `authoritative_media` records. The reusable
`resolve_authoritative_event_media(events_root, event_id)` returns validated phone-local media
only when both provenance records agree and the controlled private file exists. Otherwise it
returns the legacy network `event.mp4`.

## Android State And Retry

Only `READY` event-local media can begin sync. The durable Android ledger transitions:

```text
LOCAL_ONLY -> UPLOADING -> SYNCED
LOCAL_ONLY/FAILED -> UPLOADING -> FAILED | SYNCED
```

The local MP4 is retained in every state. App restart converts an interrupted `UPLOADING` state
to `FAILED`, so `RETRY SYNC` remains explicit and safe. The Gateway displays compact sync state
and uses an explicit `SYNC EVENT` action for this MVP; upload never blocks capture or extraction.
