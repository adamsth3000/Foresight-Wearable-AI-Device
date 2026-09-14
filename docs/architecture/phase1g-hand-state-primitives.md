# Phase 1G-G1 Hand State Primitives

Phase 1G-G1 adds conservative, provider-neutral interpretation of persisted hand landmarks. It does not infer user intent, select objects, associate the wearer, or execute commands.

## Landmark Evidence

The provider-neutral vocabulary now accepts all 21 named MediaPipe hand joints. MediaPipe's landmark indices are translated at the provider boundary; downstream code uses names such as `index_pip` and `pinky_mcp`, not provider indices. Existing seven-landmark body artifacts remain schema-version-1 compatible, but lack the joints needed for reliable flexion tests and therefore yield `UNKNOWN` semantic state.

## Geometry And Thresholds

`gestures.hand_state.HandStateConfig` centralizes all thresholds. Distances are normalized by wrist-to-middle-MCP palm scale, so translation and overall image scale do not change a classification. Finger extension combines tip-versus-MCP wrist distance with a straightness cosine. Open-hand detection requires multiple extended fingers and spread; closed-hand requires multiple flexed fingers; point requires an extended index plus flexed non-index fingers; pinch requires a short normalized thumb-index distance. Missing joints, low confidence, degenerate palm scale, or conflicting geometry return `UNKNOWN`.

## Temporal Evidence

`TemporalGestureConfig` requires a stable same-state run of at least three observations over at least 0.3 seconds. State changes and `UNKNOWN` break a run, preventing single-frame flicker or conflicting frames from becoming gesture candidates. Candidate confidence is the mean per-frame geometric confidence. `SemanticGestureEvidence` records support count, duration, consistency, peak observation, normalized feature values, and reasons.

## Artifact And Inspection

`event_gestures.json` remains schema version 1. Existing `unknown_motion` candidates and fields are unchanged. New semantic candidates add optional `semantic_evidence`, preserving the body-artifact SHA provenance chain. The existing gesture timeline displays their existing type label without creating annotations or targets.

Run a non-destructive report against an existing body artifact:

```powershell
python -m foresight_device.gestures --event-id <event-id> --report-only
```

The report prints per-frame semantic state evidence and stable semantic candidates without writing `event_gestures.json`.
