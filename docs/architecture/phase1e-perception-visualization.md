# Phase 1E Perception Visualization And Annotation

Phase 1E is a local human-in-the-loop perception editor, not merely an
annotated-video exporter. It consumes immutable Phase 1D `VisualObservation`
records from `event_perception.json`; it never rewrites model evidence.

## Boundaries

`annotation/` persists independent `HumanAnnotation` records in
`event_annotations.json`. Each record references an event and optional source
observation, records its media timestamp and action, and preserves the original
label alongside a correction. The current programmatic API implements validate,
reject, and relabel. The action vocabulary already reserves bounding-box and
mask corrections, missing-object reports, gesture/hand corrections,
point-to-object association, and selected-object state.

`annotation/relationships.py` persists a separate append-only
`event_relationship_annotations.json` artifact. `SELF_ACTOR` is always the stable
semantic identity `actor_id="self"`, `actor_role="wearer"`; it is never inferred
from a detector's arbitrary `person N` ordering. Relationship records represent
`SELF -> performs ACTION -> targets ENTITY`, retain source-evidence observation IDs,
one or more target observation IDs (or future semantic/region targets), timestamps,
human validation, notes, and provenance.

`visualization/overlay.py` is a pure timeline and style layer. It converts the
existing normalized `[x_min, y_min, x_max, y_max]` schema to pixels and associates
an observation only with nearby media timestamps. `editor_controller.py` adds
aspect-ratio-safe display-to-source coordinate conversion, deterministic smallest-box
hit testing, and annotation actions without depending on Tk widgets or Grounding DINO.

`interaction.py` holds only transient interaction state. The visual contract is:

- White: model-detected object.
- Red: object manually selected in the local editor.
- Yellow: unresolved gesture-candidate object under target-association investigation.
- Green: resolved gesture-targeted/interacting object supplied by a future gesture engine.
- Cyan: human-validated detection.
- Magenta: human-rejected detection.

Model truth, human correction, and transient interaction state are separate. A click
or yellow/green future gesture state never writes an annotation. Only Validate, Reject,
and Relabel append an independent `HumanAnnotation` immediately to
`event_annotations.json`. A persisted validation/rejection can therefore coexist with
a later transient gesture target without changing model evidence.

`editor_window.py` is a lightweight local Tkinter adapter. It uses FFmpeg only to
decode the currently requested event frame and renders structured overlays itself; it
does not require an annotated MP4. The window supports play, pause, timeline scrubbing,
timestamp display, click selection, and immediate Validate/Reject/Relabel actions.
The timestamp association window is deliberately bounded, so a three-second sampled
Grounding DINO detection is not shown indefinitely between samples.

Relabel is an explicit correction panel: selecting **Relabel...** displays the
original label, an editable combobox populated from current-event labels, **Save
Relabel**, and **Cancel**. The combobox accepts arbitrary text. Saving appends the
original and corrected labels to `event_annotations.json`; no model observation changes.
The choices are rebuilt from unique, non-empty labels in the loaded
`event_perception.json` observations whenever the panel opens, with deterministic
case-insensitive ordering while preserving the first observed display casing.

`visualization/ffmpeg_renderer.py` is the recorded-media adapter only. It
compiles the pure overlay timeline to thin FFmpeg boxes and labels, preserves
source dimensions, and stream-copies audio while re-encoding video for the
overlay. It is deliberately separate from annotation and capture code.

## Operation

```powershell
$env:PYTHONPATH = "src"
python -m foresight_device.visualization --event-id <event_id>
```

Inputs default to `data/capture/events/<event_id>/event.mp4` and
`event_perception.json`; output is `event_perception_annotated.mp4`. On Windows,
the renderer uses `C:\Windows\Fonts\arial.ttf` when available; supply
`--font-file <path>` when FFmpeg cannot resolve a font.

Phase 1E does not provide live RTSP display, clickable review, tracking IDs,
segmentation, gesture inference, wearable rendering, or active-learning export.
`media_timestamp_seconds` remains an event-media position, not an Android
monotonic-clock mapping.

## Interactive Review

```powershell
$env:PYTHONPATH = "src"
python -m foresight_device.visualization.editor --event-id <event_id>
```

The editor expects `event.mp4` and `event_perception.json` below
`data/capture/events/<event_id>/`; annotations are written immediately to
`event_annotations.json`. It requires local FFmpeg/ffprobe plus Tkinter and Pillow
from the optional perception environment. It has no browser, server, cloud, capture,
or inference dependency at runtime.

The interaction boundary anticipates the future pipeline:
camera -> object perception -> hand/gesture perception -> target association ->
visualization. `GestureAssociationDebug` is the explicit transient contract between a
future gesture/association engine and the editor. It carries detected-hand state, hand
landmarks, fingertip, gesture name/confidence, pointing origin/vector, candidate target
IDs, vector/object intersection, angular/spatial evidence, target-selection score,
candidate rejection reasons, and an optional resolved target ID. All coordinates are
normalized to the source frame, so a later UI renderer can draw hand landmarks and a
pointing line using the same viewport transform as detection boxes.

Candidate observations are yellow while association is still being investigated. A
resolved target is green. If association fails, the candidate remains yellow and its
diagnostic failure/rejection state remains available through the transient snapshot.
Gesture snapshots use the same bounded timestamp association as model observations, so
old gesture diagnostics are not rendered indefinitely during review.
This supports physical MVP debugging without recording an inference hypothesis as a
human correction. Hand tracking, gesture recognition, target-association algorithms,
and manual missing-object box drawing are intentionally not implemented yet; the
annotation action vocabulary already reserves `mark_missing_object` for that future
workflow.

## Gesture And Relationship Primitives

The visualization contract has three independent primitives: **ENTITY -> BOX/MASK**,
**ACTION/GESTURE -> CIRCLE/RING**, and **RELATIONSHIP -> LINE/ARROW**. Current object
observations provide entity boxes. `GestureAssociationDebug` reserves the normalized
hand/fingertip/pointing geometry required for a future ring and line/arrow renderer;
candidate and resolved relationship state remains yellow and green respectively.

The current editor deliberately does **not** author relationships by Shift-clicking two
ordinary entity boxes. That would falsely treat an object detection as gesture/action
evidence. The relationship schema remains available for a later gesture-aware editor.
Its intended workflow is: a gesture detector (or a future user-identified gesture ring)
produces an ACTION circle/ring; the user selects that circle; Shift-click selects an
ENTITY box/mask candidate; the candidate is yellow during association; a resolved target
is green; and only an explicit save/validate persists the SELF/action/target record.
No gesture event, yellow/green state, or relationship annotation is fabricated while no
gesture/action source exists.

Grounding DINO is only one possible observation provider. The editor consumes normalized
observation IDs and supports future OCR, segmentation, hand/pose estimation, tracking,
vision-language, and specialist perception providers. Future missing-object workflows
can add human-created region/entity annotations and then use them as relationship targets.
