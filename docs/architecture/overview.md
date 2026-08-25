# Architecture Overview

## Current Implemented State
The repository currently provides development scaffolding only:
- contributor and coding-agent guidance
- architecture and planning documentation
- Python project structure under `src/foresight_device`
- environment and logging foundations
- scaffold-level tests

No Foresight product functionality is implemented yet.

## Planned Architecture
Foresight is intended to become a long-term wearable AI assistant platform that evolves through four major stages:
- `Foresight Lab`
- `Foresight Field`
- `Foresight Intelligence`
- `Future Wearable`

The architecture should allow each stage to build on the previous one without tying the intelligence layer to any single device, sensor stack, or display.

## Core Separation
The repository is being shaped around a durable separation between:
- core intelligence and reasoning
- interaction and workflow orchestration
- capture, replay, and session processing
- hardware integrations
- display and visualization integrations

## Architectural Direction
- Early development platforms such as a computer, smartphone, and GoPro are stepping stones for development, testing, and data collection.
- Later wearable hardware is an adaptation target, not the definition of the core system.
- Display-free operation must remain possible even if optional visualization layers are added later.
- Live workflows and replay or post-processing workflows are both first-class.

## Planned Long-Term Capability Categories
The long-term vision for Foresight is broader than a wearable device demo or a computer-vision-only system. Planned capability areas include:

### Geospatial And Contextual Awareness
- determining and explaining the user's location
- contextual information and tours about surroundings
- navigation
- businesses, buildings, landmarks, and signs
- weather and time-sensitive context

### Environmental Perception
- object recognition
- plant and animal recognition
- interpreting environmental activity when appropriate

### Memory And Event Intelligence
- adventure and journey recording
- event logging
- event recall
- personal contextual memory

### Personal Organization
- note taking
- list and shopping list management
- inventory and household awareness
- personal record keeping

### Contextual Assistance
- providing relevant information
- providing advice when unusual or important events occur
- using current context, location, perception, time, and memory to determine relevance

These categories are planned long-term capabilities, not current implemented functionality.

## Repository Implication
The current repository should remain conservative until the next milestone:
- keep `src/foresight_device` as the canonical code root
- avoid premature package expansion
- document future architecture before implementing it
