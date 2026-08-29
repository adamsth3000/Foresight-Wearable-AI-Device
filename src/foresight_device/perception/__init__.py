"""Offline source-neutral event perception interfaces."""

from .detector import Detector, DetectorUnavailableError
from .event_processor import EventPerceptionError, EventPerceptionProcessor
from .frame_sampler import FfmpegFrameSampler, FrameSamplingError, sampling_timestamps
from .grounding_dino import GroundingDinoDetector
from .models import DetectorDetection, NormalizedBoundingBox, SampledFrame, VisualObservation

__all__ = [
    "Detector",
    "DetectorDetection",
    "DetectorUnavailableError",
    "EventPerceptionError",
    "EventPerceptionProcessor",
    "FfmpegFrameSampler",
    "FrameSamplingError",
    "GroundingDinoDetector",
    "NormalizedBoundingBox",
    "SampledFrame",
    "VisualObservation",
    "sampling_timestamps",
]
