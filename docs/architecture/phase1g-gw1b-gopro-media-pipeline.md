# Phase 1G-GW1-B: GoPro Media Pipeline Proof

GW1-B extends the GW1-A Android-only GoPro RTMP listener with observability,
copied encoded-media transport, an optional on-phone preview, and local MP4
recording. It remains diagnostic/local media work: it does not select a final
Foresight source, create FIELD events, extract events, upload media, or change
phone-camera capture.

## B0: Native Packet Observability

The existing FFmpeg demux-only JNI listener reports aggregate H.264/AAC packet
counts, keyframe count, PTS/DTS progress, payload sizes, stream configuration,
and a publisher generation identifier. It does not retain payloads for B0.
Each publisher connection has a new generation, allowing stale state to be
identified across disconnect and reconnect.

## B1a: Copied Encoded Transport

The JNI bridge copies encoded payloads into Kotlin and identifies their source
representations. Physical HERO8 input is H.264 AVCC (length-prefixed NAL units,
with `avcC` configuration) and raw AAC access units (with AudioSpecificConfig).

`GoProEncodedMediaTransport` owns immutable copied samples, is isolated by
publisher generation, and has a bounded capacity of 64 samples. On overflow it
drops the oldest queued sample and records separate audio/video drop counters;
a slow preview or recorder therefore cannot block native RTMP ingress. One
serialized drain fans out an owned sample to diagnostics, video preview, and
recording consumers.

## B2: Hardware Preview

`GoProH264PreviewController` is a video-only, bounded MediaCodec consumer. It
parses `avcC`, converts its input access units from AVCC to Annex-B for the
decoder, and supplies SPS/PPS as codec-specific data. Preview state is separate
from the RTMP source state and progresses independently through surface,
configuration, keyframe, and decoding states. Gateway explicitly owns either
the phone camera preview surface or the GoPro preview surface; it never renders
both sources to the same surface concurrently.

## B3: Zero-Transcode Local Recording

`GoProMp4Recorder` receives the B1a copied samples and writes a local,
app-private MP4 using `MediaMuxer`; it never decodes or re-encodes video/audio.
The source AVCC payload is preserved for B1a/B2. The recorder converts only its
private H.264 sample copy from AVCC to Annex-B before passing it to MediaMuxer.
SPS/PPS are parsed from `avcC` and supplied as `csd-0`/`csd-1`.

AAC mux configuration is derived from AudioSpecificConfig. The physical HERO8
stream reported 44100 Hz in source diagnostics while its AudioSpecificConfig
identified 48000 Hz stereo. The recorder uses the latter for muxing and stores
both reported and ASC-derived values in its sidecar rather than conflating the
two diagnostics.

Recording starts at a video keyframe, discards pre-origin audio, normalizes
per-track timestamps, and writes `gopro-<recording-id>.mp4` plus a sidecar in
app-private storage. Finalization drains accepted samples, stops and releases
MediaMuxer, renames the nonempty output, validates required H.264/AAC tracks,
computes size/SHA-256, writes metadata, and only then transitions to `SAVED`.
Finalization is idempotent: a second drain cannot validate an already-renamed
partial path and overwrite a completed recording with an error.

## Physical Validation

GW1-B0 through B3 were physically validated with a HERO8 direct RTMP publisher:

- packet/keyframe counts and timestamps advanced across reconnect generations;
- the copied transport had zero observed drops at its 64-sample bound;
- Gateway preview decoded through `c2.exynos.h264.decoder` and remained stable
  across reconnect and surface lifecycle changes;
- final zero-transcode MP4 output contained decodable H.264 1280x720 at
  30000/1001 fps and AAC 48000 Hz stereo, with no repeated malformed-H.264
  ffprobe errors.

The FFmpeg packaging and LGPL provenance remain the GW1-A package. GW1-B adds
no FFmpeg libraries and uses that package only for native RTMP/FLV demuxing.
