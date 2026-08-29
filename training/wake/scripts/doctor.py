"""Inspect a machine for safe Foresight wake-training work."""

from __future__ import annotations

import argparse
import ctypes
import os
import platform
import shutil
import subprocess

from common import (
    TRAINING_ROOT,
    add_stage_arguments,
    begin_stage,
    finish_stage,
    package_versions,
    run_root,
)


def _available_memory_bytes() -> int | None:
    if os.name != "nt":
        return None

    class MemoryStatus(ctypes.Structure):
        _fields_ = [
            ("length", ctypes.c_ulong),
            ("memory_load", ctypes.c_ulong),
            ("total_physical", ctypes.c_ulonglong),
            ("available_physical", ctypes.c_ulonglong),
            ("total_page_file", ctypes.c_ulonglong),
            ("available_page_file", ctypes.c_ulonglong),
            ("total_virtual", ctypes.c_ulonglong),
            ("available_virtual", ctypes.c_ulonglong),
            ("available_extended_virtual", ctypes.c_ulonglong),
        ]

    status = MemoryStatus()
    status.length = ctypes.sizeof(status)
    if ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status)):
        return int(status.available_physical)
    return None


def _nvidia_available() -> bool:
    if shutil.which("nvidia-smi") is None:
        return False
    return subprocess.run(["nvidia-smi", "-L"], check=False, capture_output=True).returncode == 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    add_stage_arguments(parser)
    args = parser.parse_args()
    report_path = run_root(args.profile, args.run_id) / "reports" / "doctor.json"
    if not begin_stage("doctor", args, outputs=(report_path,)):
        return 0

    disk = shutil.disk_usage(TRAINING_ROOT)
    providers: list[str] | None = None
    try:
        import onnxruntime

        providers = onnxruntime.get_available_providers()
    except ImportError:
        pass
    report = {
        "os": platform.platform(),
        "python": platform.python_version(),
        "cpu_count": os.cpu_count(),
        "available_memory_bytes": _available_memory_bytes(),
        "nvidia_cuda_available": _nvidia_available(),
        "onnxruntime_providers": providers,
        "disk_free_bytes": disk.free,
        "packages": package_versions(("openwakeword", "onnxruntime", "torch")),
        "suitability": (
            "CPU prototype stages are supported; quality training needs Linux/NVIDIA CUDA."
        ),
    }
    report_path.parent.mkdir(parents=True, exist_ok=True)
    import json

    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    finish_stage("doctor", args, outputs=(report_path,), details=report)
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
