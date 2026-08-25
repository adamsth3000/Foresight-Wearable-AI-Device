# Display Abstraction

## Current Implemented State
No display abstraction layer is implemented yet. This document defines the intended architectural direction only.

## Architectural Goal
Foresight should function without requiring a visual display while still leaving room for optional visualization systems.

## Planned Direction
- Treat displays and visualization surfaces as adapters.
- Keep audio-first interaction viable even when no visual output is present.
- Support developer visualization during Lab work without making it mandatory for the assistant architecture.
- Use phone-based visualization during development when it helps simulate or test future spatial interfaces.
- Preserve the option for a future transparent waveguide display without allowing it to define the core system.

## Expected Visualization Contexts
- developer debugging views
- phone-based spatial interface simulation
- session replay interfaces
- optional future wearable visualization

## Design Constraints
- Core intelligence should not require a display to reason, remember, or act.
- Display layers should render or summarize state rather than own it.
- Visual interfaces should remain replaceable over time.

## Possible Visualization Evolution
Planned visualization may evolve through a sequence such as:
- developer visualization
- phone-based spatial interface simulation
- future wearable rendering
- optional transparent waveguide output

This is a planning model only. It does not imply that those layers currently exist.
