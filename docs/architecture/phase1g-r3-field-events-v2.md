# Phase 1G-R3 FIELD Events V2

## Authority

FIELD event boundaries are phone-authoritative. The Android gateway creates the event UUID only after
an active local recording is available. `LAB` continues to use the laptop event-control API unchanged.

## Durable Mapping

Each FIELD event references its private recording ID. The recording metadata supplies source-session
and capture-generation provenance; the event mapping persists observed UTC and monotonic receipt
times, recording-relative start/end offsets, duration, `PHONE_FIELD` authority, and either `USER_END`
or `CAPTURE_STOP` termination. The metadata ledger is atomically persisted below Android app-private
storage and is retained across activity recreation and app restart.

Offsets are `elapsedRealtimeMillis - recordingStartElapsedRealtimeMillis`, clamped at zero. They map
the button receipt to the local recording timeline, not to a confirmed encoded frame PTS. UI dispatch,
Camera2, MediaCodec, and MP4 muxing introduce bounded but unmeasured timing uncertainty, so this
milestone does not claim frame-exact event boundaries.

## Finalization And Sync

When capture finalizes, the existing R3.2 extractor remuxes all `READY` mappings. The source recording
is preserved. A later explicit R3.3 sync uploads only READY extracted media. `phone_field` uploads
include source/session/recording provenance and create a laptop event directory only after media
validation. The resulting manifest has `event_origin: phone_field`, phone-local authoritative media,
and no fabricated `network_capture` entry.

Unfinished recordings are marked interrupted during recovery, and their unfinished event mappings are
not treated as finalized media. No local event is uploaded automatically.

## Sync History

The same app-private atomic ledger retains the latest 100 explicit sync attempts for both LAB and
FIELD events. Each record includes the event origin/authority, attempt and retry IDs, UTC start/end,
local SHA-256 and byte size, destination identity, server validation acknowledgement, returned
authoritative SHA-256, and any failure detail. A retry appends a new record linked to the prior
attempt; history retention never deletes event media.

Android records `SYNCED` only when the laptop response explicitly confirms `validated: true` and
returns the matching authoritative-media SHA-256. Interrupted in-flight attempts are recovered as
failed after a process restart. The Gateway displays the newest eight attempts in its compact Sync
History section; the durable ledger retains all 100 independently of RTSP state or operating mode.

Each visible history row opens a receipt containing origin/authority, timestamps, destination,
byte size, local and laptop SHA-256 values, explicit SHA-match result, validation flag, and failure
or retry availability. The aggregate summary counts READY/LOCAL_ONLY, SYNCED, and retryable FAILED
event media. `SYNC ALL PENDING` uses the existing single-threaded sync client to process ready local
or failed media sequentially, continues after a failure, and creates the usual independent attempt
record for every upload. It does not delete or alter retained media.
