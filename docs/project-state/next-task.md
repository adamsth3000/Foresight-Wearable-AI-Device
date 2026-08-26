# Next Task

## Recommended Next Milestone
Define the next implementation milestone after `Foresight Lab v0.3` so the simulator can exercise a further narrow workflow without introducing hardware-specific integrations.

## Why This Is Next
- A normalized interaction, session, terminal command, wake, and multi-step context flow now exists.
- The next highest-value step is extending that flow carefully while preserving the interpreter boundary, hardware independence, and small testable boundaries.
- New code should continue to avoid premature integrations or unnecessary packages.

## Suggested Scope For Planning
- decide whether the next milestone should focus on richer session events, replay-friendly event capture, or a carefully scoped simulator workflow
- define what remains simulated versus what stays explicitly deferred
- introduce new package boundaries only if the next milestone truly requires them
