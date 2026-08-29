# Next Task

## Recommended Next Milestone
Physically validate Phase 1B against the local Android/MediaMTX RTSP stream.

## Why This Is Next
- Phase 1A has already proved background phone camera/microphone transport.
- Phase 1B now needs a real 30-second pre-roll, manual trigger, and 15-second post-roll test to validate segment concatenation and manifest timing.

## Suggested Scope For Planning
- Start `python -m foresight_device --capture --source-uri <rtsp-uri>`.
- Wait 30 seconds, type `event`, wait 15 seconds, type `stop`, then verify `event.mp4`, manifest, SHA-256, and rolling-buffer expiration.
