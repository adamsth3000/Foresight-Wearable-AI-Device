# Phase 1G GW1-C1 Source-Neutral Local Media Contract

GW1-C1 preserves the validated PHONE_CAMERA FIELD path. There is no source-selection UI and
no GoPro FIELD event capture in this milestone.

`LocalMediaSourceId` explicitly distinguishes `PHONE_CAMERA` from `GOPRO_RTMP`. Every durable
local recording now has a private relative `LocalMediaLocation`, so phone recordings remain in
`files/recordings` and diagnostic GoPro recordings remain in `files/gopro_ingest_recordings`.
Both use the same app-private recording ledger; absolute paths are not persisted.

The GoPro recorder persists a segment record from arm through terminal state. Its timing evidence
uses `elapsedRealtimeNanos`: recording-arm time, first accepted/muxed H.264 keyframe receipt
time, that keyframe's source PTS in microseconds, and normalized MP4 origin (`0 us`). This
evidence is not yet used to calculate event offsets. RTMP receive, queue, and muxer latency mean
it is an anchor for later mapping, not a claim of frame-exact event alignment.

Source transport status and media availability are separate. A LIVE GoPro publisher does not mean
a finalized usable MP4 exists. Terminal GoPro recordings are retained as `AVAILABLE`,
`INTERRUPTED`, or `ERROR` ledger records with their source generation, codecs, audio rates,
duration, byte count, SHA-256 when finalized, and termination detail. The legacy B3 JSON sidecar
remains diagnostic; the shared ledger is the durable C1 metadata seam.

C2 will make FIELD session ownership and sensor lifetime source-neutral, then use this contract
and timing evidence for source-specific event mapping. C1 deliberately leaves
`LocalRecordingEventMapper` arithmetic, extraction policy, RTSP, Camera2, and all FIELD controls
unchanged.
