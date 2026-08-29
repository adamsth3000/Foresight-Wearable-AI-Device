"""Export a real prototype checkpoint to ONNX and validate it with ONNX Runtime."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from common import add_stage_arguments, begin_stage, finish_stage, run_root
from prototype_data import file_sha256
from training_compat import (
    apply_scipy_acoustics_compatibility,
    remove_unused_speechbrain_lazy_redirects,
)


def validate_onnx(model_path: Path, feature_path: Path) -> dict[str, object]:
    """Load the exported classifier and run one held-out feature through it."""
    import numpy as np
    import onnxruntime

    session = onnxruntime.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
    input_info, output_info = session.get_inputs()[0], session.get_outputs()[0]
    feature = np.load(feature_path, mmap_mode="r")[0:1].astype(np.float32)
    output = session.run([output_info.name], {input_info.name: feature})[0]
    if not np.isfinite(output).all():
        raise RuntimeError("ONNX inference produced non-finite values.")
    return {
        "passed": True,
        "input": [input_info.name, input_info.shape],
        "output": [output_info.name, output_info.shape],
        "sample_score": float(output.reshape(-1)[0]),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    add_stage_arguments(parser)
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    artifacts = run_root(args.profile, args.run_id) / "artifacts"
    checkpoint = artifacts / "hey_foresight_checkpoint.pt"
    model_path = artifacts / "hey_foresight.onnx"
    validation = artifacts / "export_validation.json"
    if not args.execute:
        print("No ONNX export ran. Re-run with --execute after real training.")
        return 0
    if not begin_stage("export", args, inputs=(checkpoint,), outputs=(model_path, validation)):
        return 0
    import torch

    apply_scipy_acoustics_compatibility()
    from openwakeword.train import Model

    remove_unused_speechbrain_lazy_redirects()
    payload = torch.load(checkpoint, map_location="cpu", weights_only=False)
    model = Model(n_classes=1, input_shape=(16, 96), model_type="dnn", layer_dim=32)
    model.model.load_state_dict(payload["state_dict"])
    model.model.eval()
    torch.onnx.export(
        model.model,
        torch.zeros((1, 16, 96)),
        model_path,
        input_names=["features"],
        output_names=["score"],
        dynamic_axes={"features": {0: "batch"}, "score": {0: "batch"}},
        opset_version=13,
    )
    features = (
        Path("training/wake/cache/features")
        / args.profile
        / args.run_id
        / "positive_validation.npy"
    )
    result = validate_onnx(model_path, features)
    result["checkpoint_sha256"] = file_sha256(checkpoint)
    result["model_sha256"] = file_sha256(model_path)
    validation.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    finish_stage(
        "export", args, inputs=(checkpoint,), outputs=(model_path, validation), details=result
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
