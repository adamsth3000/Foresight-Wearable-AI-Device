# Hardware Abstraction

## Current Implemented State
No hardware abstraction layer is implemented yet. This document defines the intended architectural direction only.

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
The repository does not yet include GoPro, phone, GPS, IMU, microphone, speaker, or embedded-device integration code.
