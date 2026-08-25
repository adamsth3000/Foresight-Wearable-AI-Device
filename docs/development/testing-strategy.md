# Testing Strategy

## Goals
- Prove the repository scaffold works as intended.
- Catch configuration drift early.
- Keep the test suite fast while the project is still infrastructure-heavy.

## Test Layers
- Unit tests cover pure helpers, configuration parsing, and logging setup logic.
- Integration tests verify repository-level defaults and package wiring.

## Current Boundaries
- No tests for AI, computer vision, voice, gesture, or hardware behavior yet.
- When those systems are introduced, they should arrive with focused tests and explicit test doubles.
