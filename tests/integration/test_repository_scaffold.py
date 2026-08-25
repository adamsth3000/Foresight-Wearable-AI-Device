from pathlib import Path


def test_expected_scaffold_files_exist() -> None:
    repo_root = Path(__file__).resolve().parents[2]

    expected_paths = [
        repo_root / "AGENTS.md",
        repo_root / "pyproject.toml",
        repo_root / ".env.example",
        repo_root / "config" / "logging.yaml",
        repo_root / "docs" / "architecture" / "development-architecture.md",
        repo_root / "docs" / "project-state" / "current-state.md",
        repo_root / "src" / "foresight_device" / "core" / "config.py",
        repo_root / "src" / "foresight_device" / "core" / "logging.py",
        repo_root / "src" / "foresight_device" / "interaction" / "service.py",
        repo_root / "src" / "foresight_device" / "sessions" / "service.py",
    ]

    for path in expected_paths:
        assert path.exists(), f"Expected scaffold path is missing: {path}"
