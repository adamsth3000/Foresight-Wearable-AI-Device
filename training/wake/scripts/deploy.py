"""Deploy an explicitly selected ONNX wake-model bundle without silent overwrite."""

from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

from common import (
    REPOSITORY_ROOT,
    add_stage_arguments,
    artifact_hash,
    finish_stage,
    manifest_path,
    write_manifest,
)


def validate_provenance(train: dict, export: dict, evaluation: dict, model_path: Path) -> None:
    """Reject deployment unless train, export, and evaluation describe one bundle."""

    if any(item.get("state") != "complete" for item in (train, export, evaluation)):
        raise ValueError("Deployment requires completed training, export, and evaluation stages.")
    if (
        len({item.get("profile") for item in (train, export, evaluation)}) != 1
        or len({item.get("run_id") for item in (train, export, evaluation)}) != 1
    ):
        raise ValueError("Deployment manifests do not share profile and run ID.")
    export_details = export.get("details", {})
    if not export_details.get("passed"):
        raise ValueError("Deployment requires a successful ONNX Runtime export validation.")
    checkpoint_hash = export_details.get("checkpoint_sha256")
    if checkpoint_hash != train.get("output_hashes", {}).get(
        next(iter(train.get("outputs", [])), "")
    ):
        raise ValueError("Training and export checkpoint hashes differ.")
    model_hash = artifact_hash(model_path)
    if model_hash != export_details.get("model_sha256"):
        raise ValueError("Selected ONNX file differs from the validated export bundle.")
    if evaluation.get("details", {}).get("onnx_sha256") != model_hash:
        raise ValueError("Evaluation did not score the selected ONNX bundle.")
    evaluation_details = evaluation.get("details", {})
    if not evaluation_details.get("positive_count") or not evaluation_details.get("negative_count"):
        raise ValueError("Deployment requires a non-empty positive and negative evaluation set.")
    if evaluation_details.get("threshold_sweep", {}).get("0.5", {}).get("recall") is None:
        raise ValueError("Deployment requires a defined evaluation recall.")
    export_manifest_hash = artifact_hash(export["_manifest_path"])
    if evaluation.get("details", {}).get("export_manifest_sha256") != export_manifest_hash:
        raise ValueError("Evaluation did not reference the current export manifest identity.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    add_stage_arguments(parser)
    parser.add_argument("--model-path", type=Path)
    parser.add_argument("--deploy", action="store_true")
    args = parser.parse_args()
    target_dir = REPOSITORY_ROOT / "models" / "wake"
    if not args.deploy or args.model_path is None:
        write_manifest(
            "deploy",
            args.profile,
            args.run_id,
            "planned",
            outputs=(target_dir / "hey_foresight.onnx",),
        )
        print("No deployment performed. Supply --model-path and --deploy explicitly.")
        return 0
    if args.model_path.name != "hey_foresight.onnx" or not args.model_path.is_file():
        raise SystemExit("--model-path must name an existing hey_foresight.onnx file.")
    required = ("train", "export", "evaluate")
    manifests = [manifest_path(stage, args.profile, args.run_id) for stage in required]
    if not all(path.is_file() for path in manifests):
        raise SystemExit("Deployment requires complete train, export, and evaluate manifests.")
    train_manifest, export_manifest, evaluation_manifest = (
        json.loads(path.read_text(encoding="utf-8")) for path in manifests
    )
    for manifest, path in zip(
        (train_manifest, export_manifest, evaluation_manifest), manifests, strict=True
    ):
        manifest["_manifest_path"] = path
    try:
        validate_provenance(train_manifest, export_manifest, evaluation_manifest, args.model_path)
    except ValueError as error:
        raise SystemExit(str(error)) from error

    bundle = [args.model_path]
    sidecar = Path(f"{args.model_path}.data")
    if sidecar.is_file():
        bundle.append(sidecar)
    targets = [target_dir / path.name for path in bundle]
    existing = [str(path) for path in targets if path.exists()]
    if existing:
        raise SystemExit(
            f"Refusing to overwrite existing deployment artifact(s): {', '.join(existing)}"
        )

    target_dir.mkdir(parents=True, exist_ok=True)
    for source, target in zip(bundle, targets, strict=True):
        shutil.copy2(source, target)
    finish_stage("deploy", args, inputs=bundle, outputs=targets)
    print("Model bundle deployed. Set FORESIGHT_WAKE_MODEL_PATH to the deployed .onnx file.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
