# Next Task

## Recommended Next Milestone
Define the next implementation milestone after `Foresight Lab v0.2` so the simulator can exercise richer session behavior without introducing hardware-specific integrations.

## Why This Is Next
- A minimal normalized interaction, session, and terminal command flow now exists.
- The next highest-value step is extending that flow carefully while preserving hardware independence and small testable boundaries.
- New code should continue to avoid premature integrations or unnecessary packages.

## Suggested Scope For Planning
- decide whether the next milestone should focus on richer session events, replay-friendly event capture, or broader simulated interaction cases inside the CLI
- define what remains simulated versus what stays explicitly deferred
- introduce new package boundaries only if the next milestone truly requires them
