"""Optional MediaPipe Tasks Hand Landmarker adapter."""

from __future__ import annotations

from io import BytesIO
from pathlib import Path

from foresight_device.perception.models import SampledFrame

from .models import Handedness, NormalizedLandmark
from .provider import ProviderUnavailableError, RawHandDetection


class MediaPipeHandsProvider:
    def __init__(self, model_path: Path) -> None:
        if not model_path.is_file():
            raise ProviderUnavailableError(f"MediaPipe hand model was not found: {model_path}")
        try:
            import mediapipe as mp  # type: ignore[import-not-found]
            import numpy as np
            from mediapipe.tasks.python import BaseOptions, vision  # type: ignore[import-not-found]
            from PIL import Image
        except ImportError as exc:
            raise ProviderUnavailableError(
                "MediaPipe Tasks requires body-perception dependencies"
            ) from exc
        self._image, self._mp, self._np = Image, mp, np
        options = vision.HandLandmarkerOptions(
            base_options=BaseOptions(model_asset_path=str(model_path)), num_hands=2
        )
        self._landmarker = vision.HandLandmarker.create_from_options(options)

    @property
    def provider_identity(self) -> str:
        return "mediapipe_tasks_hand_landmarker"

    def detect_hands(self, frame: SampledFrame) -> tuple[RawHandDetection, ...]:
        image = self._np.asarray(self._image.open(BytesIO(frame.png_bytes)).convert("RGB"))
        media_image = self._mp.Image(image_format=self._mp.ImageFormat.SRGB, data=image)
        result = self._landmarker.detect(media_image)
        aliases = {
            0: "wrist",
            4: "thumb_tip",
            5: "index_mcp",
            8: "index_tip",
            12: "middle_tip",
            16: "ring_tip",
            20: "pinky_tip",
        }
        output: list[RawHandDetection] = []
        for index, hand in enumerate(result.hand_landmarks):
            category = result.handedness[index][0] if index < len(result.handedness) else None
            label = category.category_name.lower() if category is not None else "unknown"
            side = Handedness(label) if label in {"left", "right"} else Handedness.UNKNOWN
            points = tuple(
                NormalizedLandmark(
                    name,
                    min(1.0, max(0.0, hand[key].x)),
                    min(1.0, max(0.0, hand[key].y)),
                    hand[key].z,
                )
                for key, name in aliases.items()
            )
            confidence = category.score if category is not None else 1.0
            output.append(RawHandDetection(confidence, side, confidence, points))
        return tuple(output)
