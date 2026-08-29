# Phase 1F Persistent Visual Entity Tracking

Phase 1F derives persistent-entity hypotheses from immutable Phase 1D perception.

```text
perception artifact -> observations -> tracking backend -> entity tracks
```

An `observation_id` is one detector result at one media timestamp. A `track_id` is
a machine-derived hypothesis that multiple observations represent the same real-world
entity. Tracks live in `event_tracks.json` and reference observation IDs; they never
rewrite `event_perception.json` or duplicate its detector payload.

## Event-Local Human Track Labels

`event_track_annotations.json` is a separate append-only human artifact. Each
`relabel_track` record contains an annotation ID, event ID, track ID, original track
label, corrected label, creation time, and `provenance="human"`. The latest record for
each track supplies the effective event-local human label; neither machine-derived
artifact is changed.

The editor keeps `selected_observation_id` and `selected_track_id` as separate transient
state. Selecting a tracked observation selects its track for visualization, so each visible
member of that track is red during playback or scrubbing. This creates no annotation.
Untracked observations retain observation-level selection.

The relabel UI explicitly provides **Relabel Observation** and **Relabel Track**. Display
label precedence is observation-specific human relabel, then event-local human track label,
then model observation label. A narrow correction can therefore show `delivery worker` on
one observation while `pedestrian` remains the label on its other track observations.

Human track labels are ordinary user/event-specific state. Labels such as `mom` and
`office chair` do not create identity, relationship, biometric, SELF, or cross-event
semantics. Persistent/global identity is future work and is not represented in Phase 1F.

## Baseline

`deterministic_sparse_geometry_v1` uses exact label compatibility, normalized bounding
box center displacement, an optional IoU quality term, and a configurable temporal
gap. Matching is deterministic greedy one-to-one per perception timestamp. Positive IoU
is not required because the validated event currently samples roughly every three seconds.

Defaults are `max_center_distance=0.65`, `max_time_gap_seconds=6.5`, and
`iou_weight=0.15`. These are useful initial gates, not reliable long-term identity or
re-identification. Sparse detections can move substantially, disappear, or be mislabeled.

Future replacements may increase detection frequency or use optical flow, appearance/ReID
embeddings, ByteTrack/BoT-SORT-style tracking, segmentation, VLM semantic association,
and group reasoning. A future track-correction artifact can layer merge, split, reassign,
same-entity, and different-entity corrections over `event_tracks.json` without mutating
either source artifact.

## Identity And Groups

`SELF_ACTOR` remains `actor_id="self"`, `actor_role="wearer"`. A tracked `person`
never becomes SELF automatically, including after a human track relabel. Future hand/body/pose
evidence may associate a track with SELF through optional track metadata. Optional
`parent_group_id` leaves room for future group hypotheses without creating group detection
in this phase.

Generic Foresight capability is layered beneath replaceable user-specific state. Tracking,
perception, and future wake interfaces consume abstract outcomes such as `ENTITY_TRACK` and
`WAKE_DETECTED`; they do not encode one user's names, relationships, labels, or training data.

## Operation

```powershell
$env:PYTHONPATH = "src"
python -m foresight_device.tracking --event-id <event_id>
```

The artifact records its source perception SHA-256, media SHA-256, backend, configuration,
creation timestamp, and validated observation references. When present, the Phase 1E editor
shows an effective label plus a track ID. Selecting a track renders all of its currently visible
observations red, without persisting selection or adding a color state.
