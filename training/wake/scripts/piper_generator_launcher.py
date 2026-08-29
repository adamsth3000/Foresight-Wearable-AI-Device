"""Launch the reviewed legacy Piper generator with a checkpoint-scoped loader shim."""

from __future__ import annotations

import argparse
import hashlib
import hmac
import runpy
import sys
from pathlib import Path
from typing import Any

REVIEWED_CHECKPOINT_NAME = "en-us-libritts-high.pt"


def checkpoint_sha256(path: Path) -> str:
    """Return a SHA-256 digest without loading the checkpoint."""

    digest = hashlib.sha256()
    with path.open("rb") as checkpoint:
        for block in iter(lambda: checkpoint.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _validated_checkpoint(path: Path, expected_sha256: str | None) -> tuple[Path, str]:
    resolved_path = path.resolve()
    if not resolved_path.is_file():
        raise RuntimeError(f"The trusted Piper checkpoint does not exist: {resolved_path}")
    if resolved_path.name != REVIEWED_CHECKPOINT_NAME:
        raise RuntimeError(
            f"The compatibility loader accepts only {REVIEWED_CHECKPOINT_NAME}, not "
            f"{resolved_path.name}."
        )
    digest = checkpoint_sha256(resolved_path)
    if expected_sha256 is not None and not hmac.compare_digest(digest, expected_sha256.lower()):
        raise RuntimeError(
            "The reviewed Piper checkpoint SHA-256 does not match the expected value."
        )
    return resolved_path, digest


def _generator_root(generator_path: Path | None) -> Path:
    if generator_path is None or not generator_path.is_file():
        raise RuntimeError("--generator-path must name the reviewed generate_samples.py file.")
    root = generator_path.resolve().parent
    if not (root / "piper_train").is_dir():
        raise RuntimeError("The supplied generator checkout does not contain piper_train/.")
    return root


def _preflight_imports(generator_root: Path) -> None:
    """Confirm the legacy generator's bundled VITS modules resolve locally."""

    from piper_train.vits import commons
    from piper_train.vits.models import SynthesizerTrn

    print(f"Piper preflight imports resolved from: {generator_root}")
    print(f"commons: {commons.__file__}")
    print(f"SynthesizerTrn: {SynthesizerTrn.__module__}")


def _forwarded_generator_arguments(arguments: list[str]) -> list[str]:
    """Remove this launcher's optional argparse separator before forwarding."""

    return arguments[1:] if arguments[:1] == ["--"] else arguments


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--generator-path", type=Path)
    parser.add_argument("--trusted-model", type=Path, required=True)
    parser.add_argument("--expected-checkpoint-sha256")
    parser.add_argument("--verify-checkpoint", action="store_true")
    parser.add_argument("--preflight-imports", action="store_true")
    parser.add_argument("generator_arguments", nargs=argparse.REMAINDER)
    args = parser.parse_args()

    if args.verify_checkpoint:
        _, digest = _validated_checkpoint(args.trusted_model, args.expected_checkpoint_sha256)
        print(f"Reviewed Piper checkpoint SHA-256: {digest}")
        return 0
    generator_root = _generator_root(args.generator_path)
    sys.path.insert(0, str(generator_root))
    try:
        if args.preflight_imports:
            _preflight_imports(generator_root)
            return 0
        trusted_model, digest = _validated_checkpoint(
            args.trusted_model, args.expected_checkpoint_sha256
        )
        print(f"Reviewed Piper checkpoint SHA-256: {digest}")
        generator_arguments = _forwarded_generator_arguments(args.generator_arguments)
        if not generator_arguments:
            raise RuntimeError("Pass the Piper generator arguments after --.")

        import torch

        original_load = torch.load

        def trusted_checkpoint_load(*load_args: Any, **load_kwargs: Any) -> Any:
            candidate = load_args[0] if load_args else load_kwargs.get("f")
            try:
                is_trusted_model = Path(candidate).resolve() == trusted_model
            except TypeError:
                is_trusted_model = False
            if is_trusted_model:
                # This checkpoint is reviewed and explicitly selected. Do not weaken
                # PyTorch's pickle safety for any other file.
                load_kwargs.setdefault("weights_only", False)
            return original_load(*load_args, **load_kwargs)

        torch.load = trusted_checkpoint_load
        sys.argv = [str(args.generator_path), *generator_arguments]
        runpy.run_path(str(args.generator_path), run_name="__main__")
    finally:
        sys.path.remove(str(generator_root))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
