"""Small local Tkinter recorded-event review application."""

from __future__ import annotations

import shutil
import subprocess
from io import BytesIO
from pathlib import Path
from typing import Any

from foresight_device.annotation.models import AnnotationAction

from .editor_controller import EditorController, VideoViewport
from .ffmpeg_renderer import FfmpegOverlayRenderer
from .interaction import GestureRingPrimitive, RelationshipArrowPrimitive


class EditorRuntimeError(RuntimeError):
    """Raised when local UI/video runtime requirements are unavailable."""


class EventEditor:
    """Tkinter shell around the pure editor controller and FFmpeg frame decoder."""

    def __init__(
        self,
        controller: EditorController,
        *,
        media_path: Path,
        width: int,
        height: int,
        duration_seconds: float,
        ffmpeg_executable: str = "ffmpeg",
    ) -> None:
        self._tk, self._ttk, self._image_tk, self._image = _ui_modules()
        if shutil.which(ffmpeg_executable) is None and not Path(ffmpeg_executable).is_file():
            raise EditorRuntimeError(f"ffmpeg executable was not found: {ffmpeg_executable}")
        self._controller = controller
        self._media_path = media_path
        self._source_width = width
        self._source_height = height
        self._duration_seconds = duration_seconds
        self._ffmpeg_executable = ffmpeg_executable
        self._timestamp = 0.0
        self._playing = False
        self._photo: Any | None = None

        self._root = self._tk.Tk()
        self._root.title("Foresight Perception Editor")
        self._canvas = self._tk.Canvas(self._root, width=960, height=540, background="black")
        self._canvas.pack(fill="both", expand=True)
        self._canvas.bind("<Button-1>", self._on_click)
        controls = self._tk.Frame(self._root)
        controls.pack(fill="x")
        self._tk.Button(controls, text="Play", command=self.play).pack(side="left")
        self._tk.Button(controls, text="Pause", command=self.pause).pack(side="left")
        self._slider = self._tk.Scale(
            controls,
            from_=0,
            to=duration_seconds,
            resolution=0.1,
            orient="horizontal",
            command=self._on_scrub,
        )
        self._slider.pack(side="left", fill="x", expand=True)
        self._time_label = self._tk.Label(controls, text="0.00s")
        self._time_label.pack(side="left")
        annotation_controls = self._tk.Frame(self._root)
        annotation_controls.pack(fill="x")
        self._tk.Button(annotation_controls, text="Validate", command=self._validate).pack(
            side="left"
        )
        self._tk.Button(annotation_controls, text="Reject", command=self._reject).pack(side="left")
        self._tk.Button(annotation_controls, text="Relabel...", command=self._show_relabel).pack(
            side="left"
        )
        self._relabel_controls = self._tk.LabelFrame(self._root, text="Relabel selected object")
        self._relabel_prompt = self._tk.Label(self._relabel_controls, anchor="w")
        self._relabel_prompt.pack(side="left")
        self._relabel = self._ttk.Combobox(self._relabel_controls, state="normal")
        self._relabel.pack(side="left", fill="x", expand=True)
        self._tk.Button(
            self._relabel_controls,
            text="Relabel Observation",
            command=self._confirm_observation_relabel,
        ).pack(side="left")
        self._track_relabel_button = self._tk.Button(
            self._relabel_controls, text="Relabel Track", command=self._confirm_track_relabel
        )
        self._track_relabel_button.pack(side="left")
        self._tk.Button(self._relabel_controls, text="Cancel", command=self._hide_relabel).pack(
            side="left"
        )
        self._selection_label = self._tk.Label(self._root, text="No object selected", anchor="w")
        self._selection_label.pack(fill="x")
        self._status_label = self._tk.Label(self._root, text="", anchor="w")
        self._status_label.pack(fill="x")

    def run(self) -> None:
        self._render()
        self._root.mainloop()

    def play(self) -> None:
        self._playing = True
        self._tick()

    def pause(self) -> None:
        self._playing = False

    def _tick(self) -> None:
        if not self._playing:
            return
        self._timestamp = min(self._duration_seconds, self._timestamp + 0.1)
        self._slider.set(self._timestamp)
        self._render()
        if self._timestamp >= self._duration_seconds:
            self._playing = False
        else:
            self._root.after(100, self._tick)

    def _on_scrub(self, value: str) -> None:
        self._timestamp = float(value)
        self._render()

    def _on_click(self, event: Any) -> None:
        viewport = VideoViewport(
            self._source_width,
            self._source_height,
            self._canvas.winfo_width(),
            self._canvas.winfo_height(),
        )
        selected = self._controller.click(
            event.x, event.y, timestamp_seconds=self._timestamp, viewport=viewport
        )
        self._selection_label.configure(text=_selection_text(selected))
        self._render()

    def _validate(self) -> None:
        self._annotate(AnnotationAction.VALIDATE)

    def _reject(self) -> None:
        self._annotate(AnnotationAction.REJECT)

    def _show_relabel(self) -> None:
        selected = self._controller.selected_observation
        if selected is None:
            self._status_label.configure(text="select an object before relabeling")
            return
        track_id = self._controller.selected_track_id
        scope = f" Track: {track_id}." if track_id is not None else " Untracked object."
        self._relabel_prompt.configure(
            text=f"Model: {selected.label}.{scope} New label:"
        )
        self._relabel.configure(values=list(self._controller.known_labels), state="normal")
        self._relabel.set(self._controller.selected_display_label or selected.label)
        self._track_relabel_button.configure(state="normal" if track_id is not None else "disabled")
        self._relabel_controls.pack(fill="x", before=self._selection_label)
        self._relabel.focus_set()

    def _hide_relabel(self) -> None:
        self._relabel_controls.pack_forget()

    def _confirm_observation_relabel(self) -> None:
        self._annotate(AnnotationAction.RELABEL, corrected_label=self._relabel.get().strip())
        self._hide_relabel()

    def _confirm_track_relabel(self) -> None:
        try:
            annotation = self._controller.relabel_selected_track(self._relabel.get().strip())
        except ValueError as exc:
            self._status_label.configure(text=str(exc))
            return
        self._status_label.configure(
            text=f"Saved {annotation.action.value}: {annotation.annotation_id}"
        )
        self._hide_relabel()
        self._render()

    def _annotate(self, action: AnnotationAction, *, corrected_label: str | None = None) -> None:
        try:
            annotation = self._controller.annotate_selected(action, corrected_label=corrected_label)
        except ValueError as exc:
            self._status_label.configure(text=str(exc))
            return
        self._status_label.configure(
            text=f"Saved {annotation.action.value}: {annotation.annotation_id}"
        )
        self._render()

    def _render(self) -> None:
        frame = _decode_frame(
            self._ffmpeg_executable,
            self._media_path,
            timestamp_seconds=self._timestamp,
            image_module=self._image,
        )
        canvas_width = self._canvas.winfo_width()
        canvas_height = self._canvas.winfo_height()
        frame.thumbnail((canvas_width, canvas_height))
        self._photo = self._image_tk.PhotoImage(frame)
        self._canvas.delete("all")
        image_x = (canvas_width - frame.width) / 2
        image_y = (canvas_height - frame.height) / 2
        self._canvas.create_image(image_x, image_y, anchor="nw", image=self._photo)
        viewport = VideoViewport(
            self._source_width, self._source_height, canvas_width, canvas_height
        )
        for item in self._controller.overlays_at(
            self._timestamp, width=self._source_width, height=self._source_height
        ):
            _draw_overlay(self._canvas, item, viewport)
        for primitive in self._controller.gesture_primitives_at(self._timestamp):
            _draw_interaction_primitive(self._canvas, primitive, viewport)
        self._time_label.configure(text=f"{self._timestamp:.2f}s")


def launch_editor(
    controller: EditorController,
    media_path: Path,
    *,
    ffmpeg_executable: str = "ffmpeg",
    ffprobe_executable: str = "ffprobe",
) -> None:
    """Probe an event video and launch the local interactive editor."""

    dimensions = FfmpegOverlayRenderer(
        ffmpeg_executable=ffmpeg_executable, ffprobe_executable=ffprobe_executable
    ).probe_dimensions(media_path)
    EventEditor(
        controller,
        media_path=media_path,
        width=dimensions.width,
        height=dimensions.height,
        duration_seconds=dimensions.duration_seconds,
        ffmpeg_executable=ffmpeg_executable,
    ).run()


def _ui_modules() -> tuple[Any, Any, Any, Any]:
    try:
        import tkinter as tk
        from tkinter import ttk

        from PIL import Image, ImageTk
    except ImportError as exc:
        raise EditorRuntimeError(
            "The editor requires Tkinter and Pillow. Install the optional perception environment."
        ) from exc
    return tk, ttk, ImageTk, Image


def _decode_frame(
    ffmpeg: str, media_path: Path, *, timestamp_seconds: float, image_module: Any
) -> Any:
    command = (
        ffmpeg,
        "-hide_banner",
        "-loglevel",
        "error",
        "-ss",
        f"{timestamp_seconds:.3f}",
        "-i",
        str(media_path),
        "-frames:v",
        "1",
        "-f",
        "image2pipe",
        "-vcodec",
        "png",
        "pipe:1",
    )
    result = subprocess.run(command, check=False, capture_output=True)
    if result.returncode != 0 or not result.stdout:
        raise EditorRuntimeError("ffmpeg could not decode the requested editor frame")
    return image_module.open(BytesIO(result.stdout)).convert("RGB")


def _draw_overlay(canvas: Any, item: Any, viewport: VideoViewport) -> None:
    scale = min(
        viewport.display_width / viewport.source_width,
        viewport.display_height / viewport.source_height,
    )
    offset_x = (viewport.display_width - viewport.source_width * scale) / 2
    offset_y = (viewport.display_height - viewport.source_height * scale) / 2
    box = item.pixel_box
    color = {
        "detected": "white",
        "manually_selected": "red",
        "gesture_candidate": "yellow",
        "gesture_targeted": "lime",
        "validated": "cyan",
        "rejected": "magenta",
    }[item.state.value]
    x1, y1 = offset_x + box.x * scale, offset_y + box.y * scale
    x2, y2 = offset_x + (box.x + box.width) * scale, offset_y + (box.y + box.height) * scale
    canvas.create_rectangle(x1, y1, x2, y2, outline=color, width=2)
    canvas.create_text(
        x1,
        max(10, y1 - 10),
        text=f"{item.display_label} {item.observation.confidence:.2f}",
        fill=color,
        anchor="sw",
    )


def _draw_interaction_primitive(
    canvas: Any,
    primitive: GestureRingPrimitive | RelationshipArrowPrimitive,
    viewport: VideoViewport,
) -> None:
    scale = min(
        viewport.display_width / viewport.source_width,
        viewport.display_height / viewport.source_height,
    )
    offset_x = (viewport.display_width - viewport.source_width * scale) / 2
    offset_y = (viewport.display_height - viewport.source_height * scale) / 2

    def point(value: Any) -> tuple[float, float]:
        return (
            offset_x + value.x * viewport.source_width * scale,
            offset_y + value.y * viewport.source_height * scale,
        )

    if isinstance(primitive, GestureRingPrimitive):
        x, y = point(primitive.center)
        radius = max(
            6.0,
            primitive.radius_normalized
            * min(viewport.source_width, viewport.source_height)
            * scale,
        )
        canvas.create_oval(
            x - radius, y - radius, x + radius, y + radius, outline="orange", width=2
        )
        return
    start_x, start_y = point(primitive.start)
    end_x, end_y = point(primitive.end)
    canvas.create_line(
        start_x,
        start_y,
        end_x,
        end_y,
        fill="lime" if primitive.resolved else "yellow",
        width=2,
        arrow="last",
    )


def _selection_text(observation: Any) -> str:
    if observation is None:
        return "No object selected"
    return (
        f"{observation.observation_id} | {observation.label} | {observation.confidence:.2f} | "
        f"{observation.media_timestamp_seconds:.3f}s | {observation.bounding_box.as_list()} | "
        f"{observation.detector_backend}/{observation.detector_model}"
    )
