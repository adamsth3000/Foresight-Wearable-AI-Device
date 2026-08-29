"""Small shared manifest and path helpers for wake-training stages."""

from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import json
import platform
import sys
from collections.abc import Iterable
from datetime import UTC, datetime
from pathlib import Path

TRAINING_ROOT = Path(__file__).resolve().parents[1]
REPOSITORY_ROOT = TRAINING_ROOT.parents[1]
CONFIG_ROOT = TRAINING_ROOT / "config"
OUTPUT_ROOT = TRAINING_ROOT / "outputs"
TRAINING_PROFILES = ("prototype", "prototype-v2", "prototype-v3", "quality")


def artifact_hash(path: Path) -> str:
    """Hash one file or a deterministic directory tree for stage provenance."""

    digest = hashlib.sha256()
    paths = [path] if path.is_file() else sorted(item for item in path.rglob("*") if item.is_file())
    for item in paths:
        digest.update(str(item.relative_to(path.parent)).encode())
        with item.open("rb") as source:
            for block in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(block)
    return digest.hexdigest()


def add_stage_arguments(parser: argparse.ArgumentParser) -> None:
    """Add the consistent control arguments required by every stage."""

    parser.add_argument("--profile", choices=TRAINING_PROFILES, default="prototype")
    parser.add_argument("--run-id", default="manual")
    parser.add_argument("--force", action="store_true")


def profile_config_path(profile: str) -> Path:
    """Return the versioned profile configuration file."""

    return CONFIG_ROOT / f"{profile}.yaml"


def run_root(profile: str, run_id: str) -> Path:
    """Return the directory that contains one profile execution's metadata."""

    return OUTPUT_ROOT / profile / run_id


def config_hash(profile: str) -> str:
    """Hash the base and selected profile config without parsing training YAML."""

    digest = hashlib.sha256()
    for path in (CONFIG_ROOT / "hey_foresight.base.yaml", profile_config_path(profile)):
        digest.update(path.read_bytes())
    return digest.hexdigest()


def package_versions(packages: Iterable[str]) -> dict[str, str | None]:
    """Report installed package versions without requiring optional packages."""

    versions: dict[str, str | None] = {}
    for package in packages:
        try:
            versions[package] = importlib.metadata.version(package)
        except importlib.metadata.PackageNotFoundError:
            versions[package] = None
    return versions


def manifest_path(stage: str, profile: str, run_id: str) -> Path:
    """Return the stable manifest path for a single stage execution."""

    return run_root(profile, run_id) / "manifests" / f"{stage}.json"


def completed_and_valid(stage: str, profile: str, run_id: str) -> bool:
    """Return whether a completed manifest still has every declared output."""

    path = manifest_path(stage, profile, run_id)
    if not path.is_file():
        return False
    manifest = json.loads(path.read_text(encoding="utf-8"))
    return (
        manifest.get("state") == "complete"
        and manifest.get("config_hash") == config_hash(profile)
        and all(Path(output).exists() for output in manifest.get("outputs", []))
        and manifest.get("input_hashes")
        == {str(path): artifact_hash(Path(path)) for path in manifest.get("inputs", [])}
    )


def write_manifest(
    stage: str,
    profile: str,
    run_id: str,
    state: str,
    inputs: Iterable[Path] = (),
    outputs: Iterable[Path] = (),
    details: dict[str, object] | None = None,
) -> Path:
    """Write one small, atomic stage manifest for resume decisions."""

    inputs = tuple(inputs)
    outputs = tuple(outputs)
    path = manifest_path(stage, profile, run_id)
    path.parent.mkdir(parents=True, exist_ok=True)
    manifest = {
        "stage": stage,
        "profile": profile,
        "run_id": run_id,
        "state": state,
        "completed_at": datetime.now(UTC).isoformat(),
        "config_hash": config_hash(profile),
        "inputs": [str(item) for item in inputs],
        "outputs": [str(item) for item in outputs],
        "input_hashes": {str(item): artifact_hash(item) for item in inputs if item.exists()},
        "output_hashes": {str(item): artifact_hash(item) for item in outputs if item.exists()},
        "environment": {
            "python": sys.version,
            "platform": platform.platform(),
        },
        "packages": package_versions(("openwakeword", "onnxruntime", "torch", "datasets")),
        "details": details or {},
    }
    temporary_path = path.with_suffix(".tmp")
    temporary_path.write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    temporary_path.replace(path)
    return path


def begin_stage(
    stage: str,
    args: argparse.Namespace,
    inputs: Iterable[Path] = (),
    outputs: Iterable[Path] = (),
) -> bool:
    """Write a running manifest unless a valid completed stage can be resumed."""

    if not args.force and completed_and_valid(stage, args.profile, args.run_id):
        print(f"{stage}: completed outputs are valid; skipping. Use --force to rerun.")
        return False
    write_manifest(stage, args.profile, args.run_id, "running", inputs, outputs)
    return True


def finish_stage(
    stage: str,
    args: argparse.Namespace,
    inputs: Iterable[Path] = (),
    outputs: Iterable[Path] = (),
    details: dict[str, object] | None = None,
) -> Path:
    """Mark a stage complete after its declared outputs are present."""

    missing = [str(output) for output in outputs if not output.exists()]
    if missing:
        raise RuntimeError(f"{stage} cannot complete; missing outputs: {', '.join(missing)}")
    return write_manifest(stage, args.profile, args.run_id, "complete", inputs, outputs, details)


def require_packages(packages: Iterable[str]) -> list[str]:
    """Return optional package names that are not installed."""

    return [name for name, version in package_versions(packages).items() if version is None]


def print_missing_packages(packages: Iterable[str], requirements_file: str) -> None:
    """Print an installation instruction without modifying the environment."""

    missing = require_packages(packages)
    if missing:
        print(f"Missing optional packages: {', '.join(missing)}")
        print(f"Create a separate environment, then install {requirements_file} manually.")
