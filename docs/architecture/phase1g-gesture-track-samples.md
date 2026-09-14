# Phase 1G Gesture Tracks and Samples

Gesture review keeps temporal and semantic decisions separate.

- A **Gesture Track** is a confirmed, persistent interval on the event media. It has no
  required semantic label or hand evidence.
- A **Gesture Sample** is a human-confirmed semantic annotation attached to one confirmed
  Gesture Track. It records the gesture label, human handedness, and supporting MediaPipe
  hand-track IDs.
- A **Hand Track** remains body-perception evidence only. Its MediaPipe handedness never
  determines the human semantic handedness.

The editor draws automatic proposals as outlined orange intervals. A confirmed Gesture Track
replaces its matching proposal with a solid orange interval. A manual draft is transient: its
start marker and, once complete, its outlined interval are rendered on the playback scrubber.

`event_gesture_annotations.json` remains schema version 1. New artifacts write a
`gesture_tracks` collection and attach samples with `gesture_track_id`. Older A1 annotations
without that collection load deterministically as tracks using each annotation ID and interval.
Missing legacy `human_handedness` stays unset; it is never inferred from MediaPipe output.

Deleting a sample retains its temporal track. Deleting a Gesture Track deletes its attached
sample but never changes the body-perception hand tracks.
