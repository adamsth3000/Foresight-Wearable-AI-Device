"""Recorded-event overlay models and rendering adapters."""

from .editor_controller import EditorController, VideoViewport
from .interaction import (
    GestureAssociationDebug,
    GestureCandidate,
    InteractionState,
    NormalizedPoint,
    PointingVector,
)
from .overlay import OverlayState, OverlayTimeline, PixelBoundingBox
from .perception_loader import LoadedPerception, load_perception

__all__ = [
    "LoadedPerception",
    "EditorController",
    "GestureAssociationDebug",
    "GestureCandidate",
    "InteractionState",
    "OverlayState",
    "OverlayTimeline",
    "PixelBoundingBox",
    "NormalizedPoint",
    "PointingVector",
    "VideoViewport",
    "load_perception",
]
