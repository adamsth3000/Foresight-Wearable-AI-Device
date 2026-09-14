"""Small local Tkinter recorded-event review application."""

from __future__ import annotations

import shutil
import subprocess
from io import BytesIO
from pathlib import Path
from typing import Any

from foresight_device.annotation.gesture_annotations import (
    GestureAnnotationLabel,
    HumanHandedness,
)
from foresight_device.annotation.models import AnnotationAction

from .annotation_timeline import gesture_sample_intervals, interval_at_x, timestamp_at_x
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
        self._gesture_mark_start: float | None = None
        self._gesture_mark_end: float | None = None

        self._root = self._tk.Tk()
        self._root.title("Foresight Perception Editor")
        workspace = self._tk.Frame(self._root)
        workspace.pack(fill="both", expand=True)
        left = self._tk.Frame(workspace)
        left.pack(side="left", fill="both", expand=True)
        side = self._tk.Frame(workspace, width=420)
        side.pack(side="right", fill="y")
        self._details_canvas = self._tk.Canvas(side, width=420, highlightthickness=0)
        scrollbar = self._ttk.Scrollbar(side, orient="vertical", command=self._details_canvas.yview)
        self._details_canvas.configure(yscrollcommand=scrollbar.set)
        scrollbar.pack(side="right", fill="y")
        self._details_canvas.pack(side="left", fill="both", expand=True)
        self._panel = self._tk.Frame(self._details_canvas)
        self._panel_window = self._details_canvas.create_window(
            (0, 0), window=self._panel, anchor="nw"
        )
        self._panel.bind(
            "<Configure>",
            lambda _event: self._details_canvas.configure(
                scrollregion=self._details_canvas.bbox("all")
            ),
        )
        self._details_canvas.bind(
            "<MouseWheel>",
            lambda event: self._details_canvas.yview_scroll(-int(event.delta / 120), "units"),
        )
        self._details_canvas.bind("<Configure>", self._resize_details_panel)
        self._canvas = self._tk.Canvas(left, width=960, height=540, background="black")
        self._canvas.pack(fill="both", expand=True)
        self._canvas.bind("<Button-1>", self._on_click)
        controls = self._tk.Frame(left)
        controls.pack(fill="x")
        self._tk.Button(controls, text="Play", command=self.play).pack(side="left")
        self._tk.Button(controls, text="Pause", command=self.pause).pack(side="left")
        self._playback_timeline = self._tk.Canvas(controls, height=32, background="#202020")
        self._playback_timeline.pack(side="left", fill="x", expand=True)
        self._playback_timeline.bind("<Button-1>", self._on_playback_timeline_click)
        self._playback_timeline.bind("<Configure>", lambda _event: self._render_playback_timeline())
        self._time_label = self._tk.Label(controls, text="0.00s")
        self._time_label.pack(side="left")
        annotation_controls = self._tk.Frame(self._panel)
        annotation_controls.pack(fill="x")
        self._tk.Button(annotation_controls, text="Validate", command=self._validate).pack(
            side="left"
        )
        self._tk.Button(annotation_controls, text="Reject", command=self._reject).pack(side="left")
        self._tk.Button(annotation_controls, text="Relabel...", command=self._show_relabel).pack(
            side="left"
        )
        self._relabel_controls = self._tk.LabelFrame(self._panel, text="Relabel selected object")
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
        self._gesture_controls = self._tk.LabelFrame(self._panel, text="GESTURE TRACK")
        self._gesture_controls.pack(fill="x", padx=4, pady=4)
        self._gesture_track_info = self._tk.Label(
            self._gesture_controls, text="Selected track: none", anchor="w", justify="left"
        )
        self._gesture_track_info.pack(fill="x")
        track_actions = self._tk.Frame(self._gesture_controls)
        track_actions.pack(fill="x")
        self._tk.Button(
            track_actions, text="SET SAMPLE START", command=self._mark_gesture_start
        ).pack(side="left")
        self._tk.Button(track_actions, text="SET SAMPLE END", command=self._mark_gesture_end).pack(
            side="left"
        )
        self._confirm_track_button = self._tk.Button(
            track_actions, text="CONFIRM TRACK", command=self._confirm_gesture_track
        )
        self._confirm_track_button.pack(side="left")
        sample_controls = self._tk.LabelFrame(self._panel, text="GESTURE SAMPLE")
        sample_controls.pack(fill="x", padx=4, pady=4)
        self._tk.Label(sample_controls, text="Official gesture label:", anchor="w").pack(fill="x")
        self._gesture_label = self._ttk.Combobox(sample_controls, state="readonly")
        self._gesture_label["values"] = [item.value for item in self._controller.gesture_labels]
        self._gesture_label.pack(fill="x")
        self._handedness = self._tk.StringVar(value=HumanHandedness.RIGHT.value)
        handedness_controls = self._tk.Frame(sample_controls)
        handedness_controls.pack(fill="x")
        self._tk.Label(handedness_controls, text="Handedness:").pack(side="left")
        for handedness in HumanHandedness:
            self._tk.Radiobutton(
                handedness_controls,
                text=handedness.value,
                variable=self._handedness,
                value=handedness.value,
                command=self._on_handedness_selected,
            ).pack(side="left")
        self._handedness_status = self._tk.Label(handedness_controls, text="")
        self._handedness_status.pack(side="left")
        self._track_selector = self._tk.Listbox(
            sample_controls, height=4, selectmode="multiple", exportselection=False
        )
        self._track_selector.pack(fill="x", expand=True)
        self._track_evidence_label = self._tk.Label(
            sample_controls, text="Tracks are MediaPipe perception evidence", anchor="w"
        )
        self._track_evidence_label.pack(fill="x")
        self._confirm_sample_button = self._tk.Button(
            sample_controls, text="CONFIRM SAMPLE", command=self._save_gesture_annotation
        )
        self._confirm_sample_button.pack(anchor="w")
        self._confirm_sample_reason_label = self._tk.Label(
            sample_controls, text="", anchor="w", justify="left", wraplength=390
        )
        self._confirm_sample_reason_label.pack(fill="x")
        self._confirm_sample_success_label = self._tk.Label(
            sample_controls, text="", anchor="w", justify="left", foreground="green", wraplength=390
        )
        self._confirm_sample_success_label.pack(fill="x")
        navigation_controls = self._tk.LabelFrame(self._panel, text="NAVIGATION")
        navigation_controls.pack(fill="x", padx=4, pady=4)
        management_controls = self._tk.LabelFrame(self._panel, text="MANAGEMENT")
        management_controls.pack(fill="x", padx=4, pady=4)
        self._tk.Button(
            management_controls,
            text="DELETE SAMPLE",
            command=self._delete_gesture_annotation,
        ).pack(side="left")
        self._tk.Button(
            management_controls,
            text="DELETE TRACK",
            command=self._delete_gesture_track,
        ).pack(side="left")
        self._tk.Button(
            navigation_controls,
            text="PREVIOUS TRACK",
            command=lambda: self._navigate_gesture_sample(-1),
        ).pack(side="left")
        self._tk.Button(
            navigation_controls,
            text="NEXT TRACK",
            command=lambda: self._navigate_gesture_sample(1),
        ).pack(side="left")
        self._tk.Button(
            navigation_controls, text="CLEAR SELECTION", command=self._clear_gesture_selection
        ).pack(side="left")
        self._gesture_list = self._tk.Listbox(self._panel, height=6, exportselection=False)
        self._gesture_list.pack(fill="x")
        self._gesture_list.bind("<<ListboxSelect>>", self._on_gesture_list_select)
        self._gesture_label.bind("<<ComboboxSelected>>", self._on_gesture_label_selected)
        self._track_selector.bind("<<ListboxSelect>>", self._on_supporting_tracks_selected)
        self._gesture_progress = self._tk.Label(self._panel, text="Reviewed: 0 / 0", anchor="w")
        self._gesture_progress.pack(fill="x")
        self._gesture_sample_info = self._tk.Label(
            self._panel, text="No sample selected", anchor="w"
        )
        self._gesture_sample_info.pack(fill="x")
        self._selection_label = self._tk.Label(self._panel, text="No object selected", anchor="w")
        self._selection_label.pack(fill="x")
        self._status_label = self._tk.Label(self._panel, text="", anchor="w", wraplength=400)
        self._status_label.pack(fill="x")

    def run(self) -> None:
        self._refresh_gesture_controls()
        self._render()
        self._root.mainloop()

    def _resize_details_panel(self, event: Any) -> None:
        """Keep the embedded control column usable at every window width."""

        self._details_canvas.itemconfigure(self._panel_window, width=event.width)
        self._details_canvas.configure(scrollregion=self._details_canvas.bbox("all"))

    def play(self) -> None:
        self._playing = True
        self._tick()

    def pause(self) -> None:
        self._playing = False

    def _tick(self) -> None:
        if not self._playing:
            return
        self._timestamp = min(self._duration_seconds, self._timestamp + 0.1)
        self._render()
        if self._timestamp >= self._duration_seconds:
            self._playing = False
        else:
            self._root.after(100, self._tick)

    def _mark_gesture_start(self) -> None:
        try:
            selected = self._controller.selected_gesture_sample
            if selected is not None and selected.status == "proposed":
                sample = self._controller.set_selected_gesture_sample_boundary(
                    "start", self._timestamp
                )
                self._gesture_mark_start = sample.start_timestamp_seconds
                self._gesture_mark_end = sample.end_timestamp_seconds
            else:
                draft = self._controller.set_manual_gesture_sample_boundary(
                    "start", self._timestamp
                )
                self._gesture_mark_start = draft.start_timestamp_seconds
                self._gesture_mark_end = draft.end_timestamp_seconds
        except ValueError as exc:
            self._controller.clear_semantic_confirmation_message()
            self._status_label.configure(text=str(exc))
            self._refresh_gesture_controls()
            return
        self._refresh_gesture_controls()
        self._render()

    def _mark_gesture_end(self) -> None:
        try:
            if self._controller.manual_gesture_sample_draft is not None:
                draft = self._controller.set_manual_gesture_sample_boundary("end", self._timestamp)
                self._gesture_mark_start = draft.start_timestamp_seconds
                self._gesture_mark_end = draft.end_timestamp_seconds
            else:
                sample = self._controller.set_selected_gesture_sample_boundary(
                    "end", self._timestamp
                )
                self._gesture_mark_start = sample.start_timestamp_seconds
                self._gesture_mark_end = sample.end_timestamp_seconds
        except ValueError as exc:
            self._status_label.configure(text=str(exc))
            return
        self._refresh_gesture_controls()
        self._render()

    def _confirm_gesture_track(self) -> None:
        try:
            draft = self._controller.manual_gesture_sample_draft
            if draft is not None:
                track = self._controller.confirm_manual_gesture_track()
            else:
                selected = self._controller.selected_gesture_sample
                if selected is None:
                    raise ValueError("select a proposed interval or set start and end")
                start = self._gesture_mark_start or selected.start_timestamp_seconds
                end = self._gesture_mark_end or selected.end_timestamp_seconds
                track = self._controller.confirm_gesture_track(min(start, end), max(start, end))
        except ValueError as exc:
            self._status_label.configure(text=str(exc))
            return
        self._gesture_mark_start = None
        self._gesture_mark_end = None
        self._status_label.configure(text=f"Confirmed gesture track: {track.gesture_track_id}")
        self._refresh_gesture_controls()
        self._render()

    def _save_gesture_annotation(self) -> None:
        selected = self._controller.selected_gesture_sample
        track = self._controller.selected_gesture_track
        if selected is None or track is None:
            self._controller.clear_semantic_confirmation_message()
            self._status_label.configure(
                text="select a confirmed gesture track before confirming a sample"
            )
            return
        try:
            draft = self._controller.semantic_gesture_draft
            if draft is None or draft.gesture_label is None or draft.human_handedness is None:
                raise ValueError(
                    self._controller.semantic_confirmation_reason() or "invalid sample"
                )
            annotation = self._controller.confirm_gesture_sample(
                gesture_label=draft.gesture_label,
                hand_track_ids=draft.hand_track_ids,
                human_handedness=draft.human_handedness,
            )
        except ValueError as exc:
            self._controller.clear_semantic_confirmation_message()
            self._status_label.configure(text=str(exc))
            self._refresh_gesture_controls()
            return
        self._status_label.configure(
            text=f"Confirmed {annotation.gesture_label.value}: {annotation.annotation_id}"
        )
        self._gesture_mark_start = None
        self._gesture_mark_end = None
        self._refresh_gesture_controls()
        self._render()

    def _delete_gesture_annotation(self) -> None:
        if not self._controller.delete_selected_gesture_annotation():
            self._status_label.configure(text="select a saved gesture annotation before deleting")
            return
        self._status_label.configure(text="Deleted human gesture annotation")
        self._refresh_gesture_controls()
        self._render()

    def _delete_gesture_track(self) -> None:
        if not self._controller.delete_selected_gesture_track():
            self._status_label.configure(text="select a confirmed gesture track before deleting")
            return
        self._status_label.configure(text="Deleted gesture track and any attached sample")
        self._refresh_gesture_controls()
        self._render()

    def _navigate_gesture_sample(self, direction: int) -> None:
        sample = self._controller.navigate_gesture_sample(direction)
        if sample is None:
            self._status_label.configure(text="no gesture samples proposed from body perception")
            return
        self._seek_gesture_sample(sample)

    def _clear_gesture_selection(self) -> None:
        self._gesture_mark_start = None
        self._gesture_mark_end = None
        self._controller.select_gesture_annotation(None)
        self._controller.select_gesture_sample(None)
        self._controller.clear_manual_gesture_sample_draft()
        self._refresh_gesture_controls()
        self._render()

    def _on_gesture_list_select(self, _event: Any) -> None:
        selection = self._gesture_list.curselection()
        if not selection:
            return
        sample = self._controller.gesture_samples[selection[0]]
        self._controller.select_gesture_sample(sample.proposal_id)
        self._seek_gesture_sample(sample)

    def _on_playback_timeline_click(self, event: Any) -> None:
        width = max(1, self._playback_timeline.winfo_width())
        intervals = gesture_sample_intervals(
            self._controller.gesture_samples,
            duration_seconds=self._duration_seconds,
            width=width,
        )
        selected = interval_at_x(
            intervals, x=event.x, width=width, duration_seconds=self._duration_seconds
        )
        if selected is not None:
            sample = self._controller.select_gesture_sample(selected.annotation_id)
            assert sample is not None
            self._seek_gesture_sample(sample)
            return
        self._timestamp = timestamp_at_x(
            x=event.x, width=width, duration_seconds=self._duration_seconds
        )
        self._render()

    def _seek_gesture_annotation(self, annotation: Any) -> None:
        self._timestamp = annotation.start_timestamp_seconds
        self._refresh_gesture_controls()
        self._render()

    def _seek_gesture_sample(self, sample: Any) -> None:
        self._timestamp = sample.start_timestamp_seconds
        self._gesture_mark_start = sample.start_timestamp_seconds
        self._gesture_mark_end = sample.end_timestamp_seconds
        self._refresh_gesture_controls()
        self._render()

    def _refresh_gesture_controls(self) -> None:
        self._gesture_list.delete(0, "end")
        for sample in self._controller.gesture_samples:
            self._gesture_list.insert(
                "end",
                f"{sample.status.upper()} {sample.suggested_label.value} "
                f"{sample.start_timestamp_seconds:.2f}-{sample.end_timestamp_seconds:.2f}s "
                f"[{', '.join(sample.hand_track_ids)}] observations={sample.observation_count}",
            )
        selected = self._controller.selected_gesture_sample
        track = self._controller.selected_gesture_track
        if track is None:
            self._gesture_track_info.configure(text="Selected track: UNASSIGNED")
        else:
            self._gesture_track_info.configure(
                text=(
                    f"Selected track: {track.gesture_track_id}\n"
                    f"Start: {track.start_timestamp_seconds:.2f}s  "
                    f"End: {track.end_timestamp_seconds:.2f}s"
                )
            )
        draft = self._controller.manual_gesture_sample_draft
        start = (
            self._gesture_mark_start
            if self._gesture_mark_start is not None
            else (
                draft.start_timestamp_seconds
                if draft is not None
                else (selected.start_timestamp_seconds if selected else None)
            )
        )
        end = (
            self._gesture_mark_end
            if self._gesture_mark_end is not None
            else (
                draft.end_timestamp_seconds
                if draft is not None
                else (selected.end_timestamp_seconds if selected else None)
            )
        )
        self._track_selector.delete(0, "end")
        choices = self._controller.hand_track_choices(start, end)
        semantic_draft = self._controller.semantic_gesture_draft
        selected_tracks = set(
            semantic_draft.hand_track_ids
            if semantic_draft is not None
            else (draft.hand_track_ids if draft is not None else ())
        )
        for index, choice in enumerate(choices):
            self._track_selector.insert(
                "end",
                f"{choice.hand_track_id} | MediaPipe: {choice.media_pipe_handedness} | "
                f"confidence: {choice.mean_confidence:.2f} | "
                f"{choice.start_timestamp_seconds:.2f}-{choice.end_timestamp_seconds:.2f}s",
            )
            if choice.hand_track_id in selected_tracks:
                self._track_selector.selection_set(index)
        self._track_evidence_label.configure(
            text=(
                "Supporting MediaPipe evidence: None available"
                if not choices
                else "Supporting MediaPipe Hand Tracks (optional evidence)"
            )
        )
        if semantic_draft is not None:
            self._gesture_label.set(
                semantic_draft.gesture_label.value
                if semantic_draft.gesture_label is not None
                else ""
            )
            self._handedness.set(
                semantic_draft.human_handedness.value
                if semantic_draft.human_handedness is not None
                else ""
            )
        annotation = self._controller.selected_gesture_annotation
        if annotation is not None:
            self._gesture_label.set(annotation.gesture_label.value)
            if annotation.human_handedness is not None:
                self._handedness.set(annotation.human_handedness.value)
                self._handedness_status.configure(text="")
            else:
                self._handedness_status.configure(
                    text="UNSET legacy annotation; choose a value to update"
                )
        elif draft is not None or selected is None:
            self._handedness_status.configure(text="")
        reviewed, total = self._controller.gesture_sample_progress
        self._gesture_progress.configure(
            text=f"Reviewed: {reviewed} / {total}  Remaining: {total - reviewed}"
        )
        if draft is not None:
            self._gesture_sample_info.configure(
                text=(
                    f"Manual draft: start={draft.start_timestamp_seconds}; "
                    f"end={draft.end_timestamp_seconds}; "
                    f"tracks={', '.join(draft.hand_track_ids) or '-'}"
                )
            )
        elif selected is None:
            self._gesture_sample_info.configure(text="No sample selected")
        else:
            index = next(
                index
                for index, item in enumerate(self._controller.gesture_samples, 1)
                if item.proposal_id == selected.proposal_id
            )
            official_label = (
                semantic_draft.gesture_label.value
                if semantic_draft is not None and semantic_draft.gesture_label is not None
                else "UNASSIGNED"
            )
            human_hand = (
                semantic_draft.human_handedness.value
                if semantic_draft is not None and semantic_draft.human_handedness is not None
                else "UNSET"
            )
            status = "CONFIRMED" if annotation is not None else "UNCONFIRMED"
            self._gesture_sample_info.configure(
                text=(
                    f"Sample {index} / {total}; Gesture: {official_label}; Hand: {human_hand}; "
                    f"Status: {status}; Start: {selected.start_timestamp_seconds:.2f}s; "
                    f"End: {selected.end_timestamp_seconds:.2f}s; "
                    f"Suggested: {selected.suggested_label.value}; "
                    "Supporting perception: "
                    f"{', '.join(selected.hand_track_ids) or 'None'}"
                )
            )
        self._refresh_confirm_sample_state()

    def _on_gesture_label_selected(self, _event: Any) -> None:
        try:
            self._controller.set_semantic_gesture_label(
                GestureAnnotationLabel(self._gesture_label.get())
            )
        except ValueError as exc:
            self._status_label.configure(text=str(exc))
        self._refresh_gesture_controls()

    def _on_supporting_tracks_selected(self, _event: Any) -> None:
        track = self._controller.selected_gesture_track
        if track is None:
            return
        choices = self._controller.hand_track_choices(
            track.start_timestamp_seconds, track.end_timestamp_seconds
        )
        self._controller.set_semantic_hand_tracks(
            tuple(choices[index].hand_track_id for index in self._track_selector.curselection())
        )
        self._refresh_confirm_sample_state()

    def _on_handedness_selected(self) -> None:
        self._handedness_status.configure(text="")
        try:
            self._controller.set_semantic_handedness(HumanHandedness(self._handedness.get()))
        except ValueError as exc:
            self._status_label.configure(text=str(exc))
        self._refresh_confirm_sample_state()

    def _refresh_confirm_sample_state(self) -> None:
        reason = self._confirm_sample_reason()
        self._confirm_sample_button.configure(state="normal" if reason is None else "disabled")
        self._confirm_sample_reason_label.configure(
            text="" if reason is None else f"CONFIRM SAMPLE disabled: {reason}"
        )
        self._confirm_sample_success_label.configure(
            text=self._controller.semantic_confirmation_message or ""
        )
        draft = self._controller.manual_gesture_sample_draft
        selected = self._controller.selected_gesture_sample
        track_ready = (
            draft is not None
            and draft.start_timestamp_seconds is not None
            and draft.end_timestamp_seconds is not None
        ) or (selected is not None and selected.status == "proposed")
        self._confirm_track_button.configure(state="normal" if track_ready else "disabled")
        if reason is not None:
            self._status_label.configure(text=reason)

    def _confirm_sample_reason(self) -> str | None:
        return self._controller.semantic_confirmation_reason()

    def _render_playback_timeline(self) -> None:
        """Render and hit-test human sample coverage on the actual playback scrubber."""

        self._playback_timeline.delete("all")
        width = max(1, self._playback_timeline.winfo_width())
        height = max(1, self._playback_timeline.winfo_height())
        self._playback_timeline.create_line(0, height / 2, width, height / 2, fill="#aaaaaa")
        for interval in gesture_sample_intervals(
            self._controller.gesture_samples,
            duration_seconds=self._duration_seconds,
            width=width,
        ):
            fill = interval.color if interval.status == "confirmed" else ""
            selected = self._controller.selected_gesture_sample
            outline = (
                "#fff176"
                if selected and selected.proposal_id == interval.annotation_id
                else interval.color
            )
            self._playback_timeline.create_rectangle(
                interval.start_x,
                4,
                interval.end_x,
                height - 4,
                fill=fill,
                outline=outline,
                width=3 if outline != interval.color else 2,
            )
        draft = self._controller.manual_gesture_sample_draft
        if draft is not None and draft.start_timestamp_seconds is not None:
            start_x = width * draft.start_timestamp_seconds / self._duration_seconds
            if draft.end_timestamp_seconds is None:
                self._playback_timeline.create_line(
                    start_x, 2, start_x, height - 2, fill="orange", width=3
                )
            else:
                end_x = width * draft.end_timestamp_seconds / self._duration_seconds
                self._playback_timeline.create_rectangle(
                    start_x, 4, end_x, height - 4, outline="orange", width=3
                )
        playhead_x = width * self._timestamp / self._duration_seconds
        self._playback_timeline.create_line(
            playhead_x, 0, playhead_x, height, fill="white", width=2
        )

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
        self._relabel_prompt.configure(text=f"Model: {selected.label}.{scope} New label:")
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
        self._render_playback_timeline()


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
