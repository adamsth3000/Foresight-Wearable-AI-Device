# Architectural Decisions

## 2026-08-25

### Adopt `src` Layout
- Decision: Place Python package code under `src/foresight_device`.
- Rationale: Avoid accidental imports from the repository root and keep packaging behavior explicit.

### Delay Product Functionality
- Decision: Do not implement AI, computer vision, voice, gesture, or hardware logic in the initialization phase.
- Rationale: The immediate goal is a stable development foundation and project operating model.

### Use Environment-Driven Configuration
- Decision: Resolve runtime settings from environment variables with repository defaults.
- Rationale: This keeps deployment concerns separated from source-controlled defaults.

### Start With Console Logging
- Decision: Provide centralized console logging only.
- Rationale: It is sufficient for early development and keeps the observability surface simple.

### Preserve Canonical Python Package Location
- Decision: Keep `src/foresight_device` as the canonical Python code location.
- Rationale: This preserves a clean `src` layout and avoids duplicate or competing code roots.

### Document Long-Term Architecture Before Expanding Implementation
- Decision: Expand architecture, roadmap, and project-state documentation before adding subsystem packages for future capabilities.
- Rationale: The project needs durable architectural guidance, but should avoid premature empty modules and misleading signs of implemented functionality.

### Keep Intelligence Independent From Hardware And Display
- Decision: Treat hardware and display systems as future adapters around the core assistant rather than as the architecture center.
- Rationale: This preserves portability from the Lab platform to Field workflows and later wearable hardware.
