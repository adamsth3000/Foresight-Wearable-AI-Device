# Architectural Principles

## Current Implemented State
These principles guide the project now, even though most product functionality is still planned rather than implemented.

## Principles

### Intelligence Independence
Core intelligence must remain independent from specific hardware implementations.

### Display Independence
Core intelligence must remain independent from specific display implementations.

### Adapter-Oriented Integration
Hardware integrations should eventually be modeled as adapters around the intelligence layer rather than the center of the application design.

### Optional Visualization
Display and visualization systems should remain optional. Foresight must be able to operate without a visual display requirement.

### Audio-First Interaction
Audio and voice should remain primary interaction mechanisms even if optional visualization layers exist during development or in future wearable forms.

### Development Platform Is Not Final Architecture
The initial computer, phone, and GoPro setup is a development platform for experimentation, capture, replay, and debugging. It must not hard-code the permanent system architecture.

### Live And Replay Are Both First-Class
Live field capture and post-processing or replay workflows should both be treated as first-class architectural concerns.

### Intentional Gesture Input
Planned gesture interaction should be treated as deliberate user input rather than as generic observed motion. Future wearable sensing, including possible EMG, may eventually help distinguish the user's intentional gestures from unrelated movement or gestures made by other people.

### Confirmation Before Sensitive Recording
Planned recording-oriented interactions should favor explicit confirmation before beginning structured session capture when appropriate.

### Truthful Documentation
Repository documents should clearly separate:
- implemented behavior
- planned architecture
- future concepts

Planned capabilities must never be written as if they already exist in code.
