"""Optional Hugging Face Grounding DINO detector adapter."""

from __future__ import annotations

import inspect
import logging
from collections.abc import Sequence
from io import BytesIO
from typing import Any

from .detector import DetectorUnavailableError
from .models import DetectorDetection, NormalizedBoundingBox, SampledFrame

LOGGER = logging.getLogger(__name__)


class DetectorResultError(RuntimeError):
    """Raised when a detector result cannot map every box to one semantic label."""


class GroundingDinoDetector:
    """Lazy CPU-first adapter for ``IDEA-Research/grounding-dino-base``.

    Dependencies and weights are intentionally loaded only when an operator asks
    for this backend. Unit tests instead inject a lightweight fake detector.
    """

    def __init__(
        self,
        *,
        model_id: str = "IDEA-Research/grounding-dino-base",
        device: str = "cpu",
        confidence_threshold: float = 0.35,
        text_threshold: float = 0.25,
    ) -> None:
        if not 0.0 <= confidence_threshold <= 1.0:
            raise ValueError("confidence_threshold must be within 0.0 and 1.0")
        if not 0.0 <= text_threshold <= 1.0:
            raise ValueError("text_threshold must be within 0.0 and 1.0")
        self._model_id = model_id
        self._device = device
        self._confidence_threshold = confidence_threshold
        self._text_threshold = text_threshold
        self._processor: Any | None = None
        self._model: Any | None = None

    @property
    def backend_identity(self) -> str:
        return "grounding_dino_transformers"

    @property
    def model_identity(self) -> str:
        return self._model_id

    def detect(self, frame: SampledFrame, prompts: Sequence[str]) -> Sequence[DetectorDetection]:
        """Run prompt-grounded detection and normalize pixel boxes to frame coordinates."""

        if not prompts:
            return ()
        processor, model, torch, image_module = self._load()
        image = image_module.open(BytesIO(frame.png_bytes)).convert("RGB")
        text = ". ".join(prompts) + "."
        encoded_inputs = processor(images=image, text=text, return_tensors="pt")
        input_ids = encoded_inputs["input_ids"]
        inputs = {name: value.to(self._device) for name, value in encoded_inputs.items()}
        with torch.no_grad():
            outputs = model(**inputs)
        target_sizes = torch.tensor([[frame.height, frame.width]])
        results = _post_process_grounded_detections(
            processor,
            outputs,
            input_ids,
            confidence_threshold=self._confidence_threshold,
            text_threshold=self._text_threshold,
            target_sizes=target_sizes,
        )[0]
        detections: list[DetectorDetection] = []
        boxes = tuple(results["boxes"])
        scores = tuple(results["scores"])
        LOGGER.debug(
            "Grounding DINO result cardinality: boxes=%d scores=%d labels=%s text_labels=%s",
            len(boxes),
            len(scores),
            _result_shape(results, "labels"),
            _result_shape(results, "text_labels"),
        )
        if len(scores) != len(boxes):
            raise DetectorResultError(
                "Grounding DINO returned mismatched box and score counts: "
                f"boxes={len(boxes)}, scores={len(scores)}"
            )
        if not boxes:
            return ()
        labels = _semantic_labels(results, detection_count=len(boxes))
        for box, score, label in zip(boxes, scores, labels, strict=True):
            x_min, y_min, x_max, y_max = (float(value) for value in box.tolist())
            normalized_box = NormalizedBoundingBox.from_pixel_coordinates(
                x_min,
                y_min,
                x_max,
                y_max,
                frame_width=frame.width,
                frame_height=frame.height,
            )
            matched_label = str(label)
            prompt = next(
                (item for item in prompts if item.casefold() in matched_label.casefold()), None
            )
            detections.append(
                DetectorDetection(matched_label, float(score), normalized_box, prompt)
            )
        return tuple(detections)

    def _load(self) -> tuple[Any, Any, Any, Any]:
        if self._processor is not None and self._model is not None:
            return self._processor, self._model, _torch(), _image_module()
        try:
            processor_module = __import__("transformers", fromlist=["AutoProcessor"])
            model_module = __import__(
                "transformers", fromlist=["AutoModelForZeroShotObjectDetection"]
            )
            torch = _torch()
        except ImportError as exc:
            raise DetectorUnavailableError(
                "Grounding DINO requires the optional perception dependencies: "
                "torch, transformers, and Pillow."
            ) from exc
        self._processor = processor_module.AutoProcessor.from_pretrained(self._model_id)
        self._model = model_module.AutoModelForZeroShotObjectDetection.from_pretrained(
            self._model_id
        )
        self._model.to(self._device)
        self._model.eval()
        return self._processor, self._model, torch, _image_module()


def _post_process_grounded_detections(
    processor: Any,
    outputs: Any,
    input_ids: Any,
    *,
    confidence_threshold: float,
    text_threshold: float,
    target_sizes: Any,
) -> Any:
    """Bridge the renamed object-confidence threshold in Transformers releases."""

    post_process = processor.post_process_grounded_object_detection
    threshold_name = (
        "box_threshold" if _accepts_argument(post_process, "box_threshold") else "threshold"
    )
    return post_process(
        outputs,
        input_ids,
        **{
            threshold_name: confidence_threshold,
            "text_threshold": text_threshold,
            "target_sizes": target_sizes,
        },
    )


def _accepts_argument(function: Any, name: str) -> bool:
    """Return whether a Python-callable exposes an explicit named parameter."""

    try:
        return name in inspect.signature(function).parameters
    except (TypeError, ValueError):
        return False


def _semantic_labels(result: Any, *, detection_count: int) -> tuple[str, ...]:
    """Resolve one semantic label per retained box without silently truncating data.

    Transformers releases have returned either per-detection text labels, or
    numeric per-detection labels plus a separate prompt-level ``text_labels``
    sequence. The latter is only used when each numeric label unambiguously
    indexes the provided candidate text label.
    """

    raw_text_labels = _sequence_or_none(result.get("text_labels"), "text_labels")
    raw_labels = _sequence_or_none(result.get("labels"), "labels")
    if raw_text_labels is not None and len(raw_text_labels) == detection_count:
        if all(isinstance(label, str) for label in raw_text_labels):
            return tuple(raw_text_labels)
        raise DetectorResultError("Grounding DINO text_labels are not semantic strings")
    if raw_labels is not None and len(raw_labels) == detection_count:
        if all(isinstance(label, str) for label in raw_labels):
            return tuple(raw_labels)
        candidate_labels = _candidate_text_labels(raw_text_labels)
        if candidate_labels is not None and all(_is_integer_label(label) for label in raw_labels):
            numeric_labels = tuple(int(label) for label in raw_labels)
            if all(0 <= label < len(candidate_labels) for label in numeric_labels):
                return tuple(candidate_labels[label] for label in numeric_labels)
    text_count = len(raw_text_labels) if raw_text_labels is not None else 0
    label_count = len(raw_labels) if raw_labels is not None else 0
    raise DetectorResultError(
        "Grounding DINO returned ambiguous detection labels: "
        f"boxes={detection_count}, labels={label_count}, text_labels={text_count}"
    )


def _sequence_or_none(value: Any, name: str) -> tuple[Any, ...] | None:
    """Convert an array-like result value into a sequence, rejecting scalar strings."""

    if value is None:
        return None
    if isinstance(value, (str, bytes)):
        raise DetectorResultError(f"Grounding DINO {name} must be a sequence, not a scalar string")
    try:
        return tuple(value)
    except TypeError as exc:
        raise DetectorResultError(f"Grounding DINO {name} is not a sequence") from exc


def _candidate_text_labels(raw_labels: tuple[Any, ...] | None) -> tuple[str, ...] | None:
    """Accept either a candidate list or one batched candidate-list wrapper."""

    if raw_labels is None:
        return None
    if all(isinstance(label, str) for label in raw_labels):
        return tuple(raw_labels)
    if len(raw_labels) == 1 and not isinstance(raw_labels[0], (str, bytes)):
        nested = _sequence_or_none(raw_labels[0], "text_labels")
        if nested is not None and all(isinstance(label, str) for label in nested):
            return tuple(nested)
    return None


def _is_integer_label(value: Any) -> bool:
    """Recognize Python or tensor-like scalar class indexes without accepting floats."""

    if isinstance(value, bool):
        return False
    if isinstance(value, int):
        return True
    try:
        return value.ndim == 0 and int(value) == value.item()
    except (AttributeError, TypeError, ValueError):
        return False


def _result_shape(result: Any, key: str) -> str:
    """Return a safe debug-only shape summary that preserves nested wrappers."""

    value = result.get(key)
    if value is None:
        return "missing"
    try:
        outer = tuple(value)
    except TypeError:
        return "scalar"
    nested_lengths: list[str] = []
    for item in outer:
        if isinstance(item, (str, bytes)):
            continue
        try:
            nested_lengths.append(str(len(item)))
        except TypeError:
            continue
    nested = f", nested=({', '.join(nested_lengths)})" if nested_lengths else ""
    return f"outer={len(outer)}{nested}"


def _torch() -> Any:
    return __import__("torch")


def _image_module() -> Any:
    try:
        return __import__("PIL.Image", fromlist=["open"])
    except ImportError as exc:
        raise DetectorUnavailableError(
            "Grounding DINO requires the optional Pillow dependency."
        ) from exc
