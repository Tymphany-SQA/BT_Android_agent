#!/usr/bin/env python3
from __future__ import annotations

import json
import subprocess
import sys
import tkinter as tk
from pathlib import Path
from tkinter import ttk


ROOT = Path("/Users/sam/code/BT_Android_Agent2")
SUMMARY_SCRIPT = ROOT / "tools" / "adb_bt_summary.py"


class SummaryApp:
    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        self.root.title("BT Summary Panel")
        self.root.geometry("760x620")

        self.device_var = tk.StringVar()
        self.interval_var = tk.StringVar(value="3")
        self.watch_var = tk.BooleanVar(value=False)
        self.status_var = tk.StringVar(value="Idle")

        self.watch_job: str | None = None
        self.device_options: list[dict[str, object]] = []

        self._build_ui()
        self.refresh_summary()

    def _build_ui(self) -> None:
        container = ttk.Frame(self.root, padding=16)
        container.pack(fill="both", expand=True)

        controls = ttk.Frame(container)
        controls.pack(fill="x")

        ttk.Label(controls, text="Device").grid(row=0, column=0, sticky="w")
        self.device_combo = ttk.Combobox(
            controls,
            textvariable=self.device_var,
            width=32,
            state="readonly",
        )
        self.device_combo.grid(row=0, column=1, padx=(8, 16), sticky="ew")
        self.device_combo.bind("<<ComboboxSelected>>", lambda _event: self.refresh_summary())

        ttk.Label(controls, text="Watch Interval (s)").grid(row=0, column=2, sticky="w")
        interval_entry = ttk.Entry(controls, textvariable=self.interval_var, width=8)
        interval_entry.grid(row=0, column=3, padx=(8, 16), sticky="w")

        refresh_button = ttk.Button(controls, text="Refresh", command=self.refresh_summary)
        refresh_button.grid(row=0, column=4, padx=(0, 8))

        watch_button = ttk.Checkbutton(
            controls,
            text="Watch",
            variable=self.watch_var,
            command=self._toggle_watch,
        )
        watch_button.grid(row=0, column=5, sticky="w")

        controls.columnconfigure(1, weight=1)

        status_row = ttk.Frame(container)
        status_row.pack(fill="x", pady=(12, 0))
        ttk.Label(status_row, text="Status").pack(side="left")
        ttk.Label(status_row, textvariable=self.status_var).pack(side="left", padx=(8, 0))

        cards = ttk.LabelFrame(container, text="Summary", padding=12)
        cards.pack(fill="x", pady=(12, 0))

        self.fields = {}
        labels = [
            ("Device", "device"),
            ("Classic MAC", "address"),
            ("LE MAC", "le_address"),
            ("LE Name", "le_name"),
            ("Manufacturer", "manufacturer"),
            ("Model", "model"),
            ("Software Version", "software_version"),
            ("Hardware Version", "hardware_version"),
            ("Companion App", "companion_app"),
            ("Active Audio Device", "is_active_audio_device"),
            ("Connection State", "connection_state"),
            ("Playing", "is_playing"),
            ("Current Codec", "current_codec"),
            ("Selectable Codecs", "selectable_codecs"),
            ("Local Capabilities", "local_capabilities"),
            ("Optional Codec Support", "optional_codec_support"),
            ("Optional Codec Enabled", "optional_codec_enabled"),
        ]
        for row, (label, key) in enumerate(labels):
            ttk.Label(cards, text=label).grid(row=row, column=0, sticky="nw", pady=2)
            value = ttk.Label(cards, text="-", wraplength=500, justify="left")
            value.grid(row=row, column=1, sticky="nw", padx=(12, 0), pady=2)
            self.fields[key] = value
        cards.columnconfigure(1, weight=1)

        raw_frame = ttk.LabelFrame(container, text="Raw JSON", padding=12)
        raw_frame.pack(fill="both", expand=True, pady=(12, 0))

        self.raw_text = tk.Text(raw_frame, wrap="word", height=18)
        self.raw_text.pack(side="left", fill="both", expand=True)
        self.raw_text.configure(state="disabled")
        scrollbar = ttk.Scrollbar(raw_frame, orient="vertical", command=self.raw_text.yview)
        scrollbar.pack(side="right", fill="y")
        self.raw_text.configure(yscrollcommand=scrollbar.set)

    def _summary_command(self) -> list[str]:
        cmd = [sys.executable, str(SUMMARY_SCRIPT), "--json"]
        device = self.device_var.get().strip()
        if device:
            selected = next((item for item in self.device_options if str(item.get("label", "")) == device), None)
            query = str(selected.get("address")) if selected and selected.get("address") else device
            cmd.extend(["--device", query])
        return cmd

    def _list_command(self) -> list[str]:
        return [sys.executable, str(SUMMARY_SCRIPT), "--list-json"]

    def refresh_summary(self) -> None:
        self._refresh_device_options()
        try:
            result = subprocess.run(
                self._summary_command(),
                check=True,
                capture_output=True,
                text=True,
                cwd=str(ROOT),
            )
            payload = json.loads(result.stdout)
        except subprocess.CalledProcessError as exc:
            self.status_var.set(f"Refresh failed: {exc.stderr.strip() or exc}")
            self._set_raw_text(exc.stderr.strip() or str(exc))
            self._set_empty_fields()
            self._schedule_next_if_needed()
            return
        except json.JSONDecodeError as exc:
            self.status_var.set(f"Refresh failed: invalid JSON ({exc})")
            self._set_raw_text(result.stdout if "result" in locals() else str(exc))
            self._set_empty_fields()
            self._schedule_next_if_needed()
            return

        self.status_var.set("Updated")
        self._render_payload(payload)
        self._set_raw_text(json.dumps(payload, indent=2, ensure_ascii=False))
        self._schedule_next_if_needed()

    def _refresh_device_options(self) -> None:
        try:
            result = subprocess.run(
                self._list_command(),
                check=True,
                capture_output=True,
                text=True,
                cwd=str(ROOT),
            )
            items = json.loads(result.stdout)
        except Exception:
            return
        if not isinstance(items, list):
            return
        self.device_options = items
        labels = [str(item.get("label", "")) for item in items]
        self.device_combo["values"] = labels
        current = self.device_var.get().strip()
        if current and current in labels:
            return
        active = next((item for item in items if item.get("is_active_audio_device")), None)
        preferred = active or (items[0] if items else None)
        if preferred:
            self.device_var.set(str(preferred.get("label", "")))

    def _render_payload(self, payload: dict[str, object]) -> None:
        device_name = payload.get("name") or payload.get("model") or "Unknown"
        self.fields["device"].configure(text=str(device_name))
        for key in self.fields:
            if key == "device":
                continue
            value = payload.get(key)
            if isinstance(value, list):
                text = ", ".join(str(item) for item in value) if value else "-"
            elif isinstance(value, bool):
                text = "Yes" if value else "No"
            elif value is None or value == "":
                text = "-"
            else:
                text = str(value)
            self.fields[key].configure(text=text)

    def _set_raw_text(self, text: str) -> None:
        self.raw_text.configure(state="normal")
        self.raw_text.delete("1.0", "end")
        self.raw_text.insert("1.0", text)
        self.raw_text.configure(state="disabled")

    def _set_empty_fields(self) -> None:
        for value in self.fields.values():
            value.configure(text="-")

    def _toggle_watch(self) -> None:
        if self.watch_var.get():
            self.status_var.set("Watching...")
            self.refresh_summary()
        elif self.watch_job is not None:
            self.root.after_cancel(self.watch_job)
            self.watch_job = None
            self.status_var.set("Watch stopped")

    def _schedule_next_if_needed(self) -> None:
        if not self.watch_var.get():
            return
        if self.watch_job is not None:
            self.root.after_cancel(self.watch_job)
        interval = self._interval_ms()
        self.watch_job = self.root.after(interval, self.refresh_summary)

    def _interval_ms(self) -> int:
        try:
            seconds = max(1, int(float(self.interval_var.get().strip())))
        except ValueError:
            seconds = 3
            self.interval_var.set("3")
        return seconds * 1000


def main() -> int:
    root = tk.Tk()
    SummaryApp(root)
    root.mainloop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
