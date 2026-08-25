# Getting Started

## Prerequisites
- Python 3.11 or newer
- A virtual environment tool such as `venv`

## Local Setup
1. Create a virtual environment:
   `python -m venv .venv`
2. Activate it:
   Windows PowerShell: `.venv\Scripts\Activate.ps1`
3. Install development dependencies:
   `python -m pip install -e .[dev]`
4. Copy environment defaults if needed:
   `Copy-Item .env.example .env`

## Common Commands
- Run tests: `pytest`
- Run linting: `ruff check .`
- Run type checking: `mypy src`

## Early-Phase Guidance
- Infrastructure-only changes are expected in the current phase.
- New runtime dependencies should be justified in documentation or an ADR-style note.
