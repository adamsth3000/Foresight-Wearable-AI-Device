# Hardware Abstraction

## Current Implemented State
The optional Lab voice adapters provide separate microphone-to-transcript and openWakeWord-based fixed-phrase wake paths. The optional Lab output adapter emits a Windows wake cue and can use local TTS. These adapters are replaceable and do not expose wake-provider, microphone, or output details to the interaction core. `WakeInputAdapter` remains the stable Foresight boundary. Phase 1A adds a native Android gateway isolated under `platforms/android/`; its RTSP output is consumed by the source-neutral Python `capture` package rather than leaking Android types into the core.

## Architectural Goal
Foresight intelligence should survive hardware transitions without requiring the core reasoning model to be redesigned.

## Planned Direction
- Treat physical or development-platform inputs as adapters.
- Avoid coupling the intelligence layer directly to a single device vendor or hardware stack.
- Allow the early computer, smartphone, and GoPro setup to act as a development and data-collection environment.
- Preserve a migration path toward later wearable hardware.

## Expected Adapter Types
- capture adapters
- sensor adapters
- interaction input adapters
- output or feedback adapters

## Design Constraints
- Hardware details should be normalized before reaching core intelligence.
- Session recording should not assume a final wearable form factor.
- Future embedded sensors or optional EMG inputs should be additive, not architecture-defining.
- Future gesture-related sensing should support intentional user input rather than treating all observed motion as equivalent.
- Possible EMG-based input is a future aid for identifying the user's deliberate gestures, not a current implementation detail.

## Not Implemented Yet
The repository does not yet include GoPro, GPS, IMU, wearable, Bluetooth, bone-conduction, haptic, or embedded-device integration code. The current Android gateway is a development transport source, not permanent product hardware. The Lab wake adapter is a controlled local prototype, not production wake-word monitoring; it releases microphone ownership before fixed-duration command capture. The Lab output adapter does not provide streaming or background playback.
