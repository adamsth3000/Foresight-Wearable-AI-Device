# Phase 1D Event Perception

## Scope

Phase 1D processes promoted evidence offline. It consumes an existing
`data/capture/events/<event_id>/event.mp4` and writes a replaceable
`event_perception.json` beside it. It does not change Android capture, RTSP,
MediaMTX, FFmpeg ingress, rolling-buffer, event-promotion, or telemetry behavior.

## Pipeline

`EventPerceptionProcessor` asks a source-neutral `FrameSampler` for ordered,
in-memory `SampledFrame` values. The default `FfmpegFrameSampler` probes the
media and decodes one PNG frame per requested timestamp to stdout; it does not
retain decoded JPEG or PNG files. Sampling defaults to one frame per second and
includes timestamp zero for a short event.

A `Detector` adapter receives each sampled frame and the configured prompts, and
returns normalized `DetectorDetection` values. Observations use the canonical
bounding-box order `[x_min, y_min, x_max, y_max]`, clamped to `0.0..1.0` in decoded
frame coordinates. Results are deterministically ordered by media timestamp,
frame index, label, confidence, and stable observation ID.

`event_perception.json` records the event ID, event media filename, manifest
SHA-256 when available, processing configuration, frame count, and structured
observations. It never modifies `manifest.json`.

## Timing

`media_timestamp_seconds` is the position of a decoded frame in `event.mp4`. It
is not an Android `elapsedRealtimeNanos()` timestamp and Phase 1D v1 does not
claim an exact mapping between media PTS and Phase 1C sensor timing anchors.
That mapping requires future encoded-media PTS timing work.

## Detector Backends

The optional `GroundingDinoDetector` is a Transformers implementation using
`IDEA-Research/grounding-dino-base`, requested with
`foresight-device[perception]`. It loads PyTorch, Pillow, Transformers, and
model weights lazily only when the backend is selected. The base installation
and unit tests have no ML dependency, network request, or model-weight download.

On the present Windows on Arm development environment, use a compatible native
PyTorch/Python environment before attempting local CPU inference. The current
Python 3.14 runtime should be treated as an adapter-development/test runtime,
not an assumed supported Grounding DINO inference environment.

Future adapters can add specialist detectors, text-region detection followed by
OCR, or live-frame sampling while preserving `VisualObservation`. Phase 1D does
not implement OCR, scene narration, cloud inference, or live inference.

## Operation

The dedicated entry point avoids the dirty top-level wake CLI:

```powershell
python -m foresight_device.perception --event-id <event_id>
```

For dependency-free pipeline and FFmpeg verification only, use:

```powershell
python -m foresight_device.perception --event-id <event_id> --backend empty
```
