# Foresight Lab

## Phase Intent
Foresight Lab is the controlled development environment for early experimentation before the project becomes a field-capable system.

## Planned Scope
- development with a computer, smartphone, and GoPro
- controlled experimentation with live and replayable video
- voice command experimentation
- intentional gesture simulation and recording
- developer visualization and debugging

## Architectural Role
This phase is a development platform. It is useful for experimentation, instrumentation, and replay, but it is not the final system architecture.

## Planned Interaction Example
The Lab phase may eventually explore interactions such as:

User:
"Hey Foresight, I'm going on an adventure."

Foresight:
"Would you like me to record this event?"

If the user confirms, Foresight would begin a structured recording session and provide an appropriate confirmation cue, such as an audible beep.

This is a planned interaction concept only. It is not currently implemented functionality.

## Architectural Meaning Of The Example
This example is intended to illustrate future work around:
- intent recognition
- contextual interaction
- confirmation before recording
- session state transition
- multimodal event or session capture

## Current Implemented State
The repository now includes small implemented Lab milestones:
- normalized simulated interaction models for voice, gesture, text, and system modalities
- explicit intent resolution for `START_ADVENTURE`, `CONFIRM_YES`, `CONFIRM_NO`, and `UNKNOWN`
- a minimal adventure-session lifecycle with confirmation before recording
- normalized session lifecycle events for proposed, confirmed, started, cancelled, and ended states
- a minimal terminal-based command simulator that converts typed text into normalized `TEXT` interactions and displays assistant responses
- compact session inspection through a terminal `status` command
- deterministic handling of the exact wake phrase `Hey Foresight`, followed by a simulated `[BEEP]` acknowledgement and `LISTENING_FOR_COMMAND` state
- a small `IntentInterpreter` boundary with a deterministic implementation for constrained example phrases
- explicit pending interaction context for adventure confirmation, note content, and shopping-item content
- simulated note and shopping flows that retain normalized captured content in memory for a future persistence consumer
- an optional command-triggered microphone-to-transcript adapter that creates normalized `VOICE` interactions with a `MICROPHONE` source

The microphone adapter does not own wake handling, intent interpretation, pending context, or session logic. The typed `voice` command is a Lab development control that triggers one fixed-duration capture; its transcript is processed by the existing interaction system. The wake phrase is intentionally separate from ordinary intent interpretation. The current phrase rules are demonstrations for Lab testing, not a permanent language model or an exhaustive command vocabulary. The architecture leaves room for future priority handling, but no emergency path is implemented.

This implemented scope is still intentionally limited. It does not include spoken assistant responses, actual audio playback, continuous listening, production wake-word detection, gesture recognition, media ingestion, GPS integration, replay, persistence, AI or NLP systems, or hardware adapters beyond the optional Lab microphone input.
