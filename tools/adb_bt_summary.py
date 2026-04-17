#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from dataclasses import asdict
from dataclasses import dataclass, field


ADDR_RE = r"[0-9A-F]{2}(?::[0-9A-F]{2}){5}"


@dataclass
class DeviceSummary:
    address: str
    name: str | None = None
    le_address: str | None = None
    le_name: str | None = None
    manufacturer: str | None = None
    model: str | None = None
    software_version: str | None = None
    hardware_version: str | None = None
    companion_app: str | None = None
    optional_codec_support: str | None = None
    optional_codec_enabled: str | None = None
    connection_state: str | None = None
    is_playing: str | None = None
    current_codec: str | None = None
    selectable_codecs: list[str] = field(default_factory=list)
    local_capabilities: list[str] = field(default_factory=list)
    is_active_audio_device: bool = False


def run_adb_dumpsys() -> str:
    result = subprocess.run(
        ["adb", "shell", "dumpsys", "bluetooth_manager"],
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout


def parse_bonded_devices(text: str) -> dict[str, tuple[str | None, str | None]]:
    devices: dict[str, tuple[str | None, str | None]] = {}
    bonded_match = re.search(r"Bonded devices:\n(?P<body>(?:\s+.+\n)+)", text)
    if not bonded_match:
        return devices
    for line in bonded_match.group("body").splitlines():
        m = re.search(rf"\s+({ADDR_RE})\s+\[\s*(.*?)\s*\]\s*(.*)$", line)
        if not m:
            continue
        address, mode, name = m.groups()
        devices[address] = (mode.strip(), name.strip() or None)
    return devices


def parse_metadata_blocks(text: str) -> dict[str, DeviceSummary]:
    summaries: dict[str, DeviceSummary] = {}
    for match in re.finditer(rf"\s+({ADDR_RE}) \{{(.*?)\}}\n", text, re.DOTALL):
        address, body = match.groups()
        summary = summaries.setdefault(address, DeviceSummary(address=address))
        meta_match = re.search(r"custom metadata\((.*?)\)$", body)
        if not meta_match:
            continue
        raw_meta = meta_match.group(1)
        fields = {}
        for part in raw_meta.split("|"):
            if "=" not in part:
                continue
            key, value = part.split("=", 1)
            fields[key.strip()] = value.strip()
        summary.manufacturer = normalize_value(fields.get("manufacturer_name"))
        summary.model = normalize_value(fields.get("model_name"))
        summary.software_version = normalize_value(fields.get("software_version"))
        summary.hardware_version = normalize_value(fields.get("hardware_version"))
        summary.companion_app = normalize_value(fields.get("companion_app"))
        opt = re.search(r"optional codec\(support=([^|]+)\|enabled=([^)]+)\)", body)
        if opt:
            summary.optional_codec_support = opt.group(1).strip()
            summary.optional_codec_enabled = opt.group(2).strip()
    return summaries


def parse_active_addresses(text: str) -> set[str]:
    active = set(re.findall(rf"mActiveDevice:\s+({ADDR_RE})", text))
    active.update(re.findall(rf"mCurrentDevice:\s+({ADDR_RE})", text))
    return active


def parse_a2dp_state(text: str, summaries: dict[str, DeviceSummary]) -> None:
    pattern = re.compile(
        rf"=== A2dpStateMachine for ({ADDR_RE}) .*?===\n(?P<body>.*?)(?=\n\s*=== A2dpStateMachine|\n\s*Profile:|\Z)",
        re.DOTALL,
    )
    for match in pattern.finditer(text):
        address = match.group(1)
        body = match.group("body")
        summary = summaries.setdefault(address, DeviceSummary(address=address))
        conn = re.search(r"mConnectionState:\s*([^,]+)", body)
        if conn:
            summary.connection_state = conn.group(1).strip()
        playing = re.search(r"mIsPlaying:\s*(\w+)", body)
        if playing:
            summary.is_playing = playing.group(1).strip()
        codec = re.search(r"mCodecConfig:\s*\{codecName:([^,]+)", body)
        if codec:
            summary.current_codec = codec.group(1).strip()
        summary.selectable_codecs = re.findall(r"mCodecsSelectableCapabilities:\s*(?:\n\s+\{codecName:([^,]+).*)+", body)
        summary.local_capabilities = re.findall(r"mCodecsLocalCapabilities:\[(?:\{codecName:([^,]+).*)", body)

        if not summary.local_capabilities:
            summary.local_capabilities = re.findall(r"\{codecName:([^,]+)", body.split("mCodecsSelectableCapabilities:")[0])

        selectable_block = re.search(r"mCodecsSelectableCapabilities:\n(?P<body>(?:\s+\{codecName:[^}]+\}\n?)*)", body)
        if selectable_block:
            summary.selectable_codecs = re.findall(r"\{codecName:([^,]+)", selectable_block.group("body"))
        local_block = re.search(r"mCodecsLocalCapabilities:\[(?P<body>.*?)\],mCodecsSelectableCapabilities", body, re.DOTALL)
        if local_block:
            summary.local_capabilities = re.findall(r"\{codecName:([^,]+)", local_block.group("body"))


def normalize_value(value: str | None) -> str | None:
    if value is None or value == "null":
        return None
    return value


def choose_target(
    summaries: dict[str, DeviceSummary],
    bonded: dict[str, tuple[str | None, str | None]],
    active_addresses: set[str],
    query: str | None,
) -> DeviceSummary | None:
    for address, summary in summaries.items():
        mode, bonded_name = bonded.get(address, (None, None))
        if bonded_name and not summary.name:
            summary.name = bonded_name
        if mode == "DUAL":
            for le_addr, (le_mode, le_name) in bonded.items():
                if le_mode == "LE" and le_name and bonded_name and le_name.startswith(bonded_name):
                    summary.le_address = le_addr
                    summary.le_name = le_name
        if address in active_addresses:
            summary.is_active_audio_device = True

    ordered = list(summaries.values())
    if query:
        q = query.lower()
        for summary in ordered:
            haystack = " ".join(
                filter(
                    None,
                    [
                        summary.address,
                        summary.name,
                        summary.le_address,
                        summary.le_name,
                        summary.model,
                        summary.manufacturer,
                    ],
                )
            ).lower()
            if q in haystack:
                return summary
        return None

    for summary in ordered:
        if summary.is_active_audio_device:
            return summary
    scored = sorted(
        ordered,
        key=lambda summary: (
            bool(summary.connection_state),
            bool(summary.current_codec),
            bool(summary.model or summary.name),
            bool(summary.manufacturer),
        ),
        reverse=True,
    )
    return scored[0] if scored else None


def format_summary(summary: DeviceSummary) -> str:
    lines = [
        f"Device: {summary.name or summary.model or 'Unknown'}",
        f"Classic MAC: {summary.address}",
    ]
    if summary.le_address:
        lines.append(f"LE MAC: {summary.le_address}")
    if summary.le_name:
        lines.append(f"LE Name: {summary.le_name}")
    lines.append(f"Active Audio Device: {'Yes' if summary.is_active_audio_device else 'No'}")
    lines.append("")
    lines.append("Metadata")
    lines.append(f"  Manufacturer: {summary.manufacturer or '-'}")
    lines.append(f"  Model: {summary.model or '-'}")
    lines.append(f"  Software Version: {summary.software_version or '-'}")
    lines.append(f"  Hardware Version: {summary.hardware_version or '-'}")
    lines.append(f"  Companion App: {summary.companion_app or '-'}")
    lines.append("")
    lines.append("A2DP")
    lines.append(f"  Connection State: {summary.connection_state or '-'}")
    lines.append(f"  Playing: {summary.is_playing or '-'}")
    lines.append(f"  Current Codec: {summary.current_codec or '-'}")
    lines.append(f"  Optional Codec Support: {summary.optional_codec_support or '-'}")
    lines.append(f"  Optional Codec Enabled: {summary.optional_codec_enabled or '-'}")
    lines.append(f"  Selectable Codecs: {', '.join(summary.selectable_codecs) if summary.selectable_codecs else '-'}")
    lines.append(f"  Local Capabilities: {', '.join(summary.local_capabilities) if summary.local_capabilities else '-'}")
    return "\n".join(lines)


def format_metadata(summary: DeviceSummary) -> str:
    return "\n".join(
        [
            f"Device: {summary.name or summary.model or 'Unknown'}",
            f"Classic MAC: {summary.address}",
            f"LE MAC: {summary.le_address or '-'}",
            f"LE Name: {summary.le_name or '-'}",
            f"Manufacturer: {summary.manufacturer or '-'}",
            f"Model: {summary.model or '-'}",
            f"Software Version: {summary.software_version or '-'}",
            f"Hardware Version: {summary.hardware_version or '-'}",
            f"Companion App: {summary.companion_app or '-'}",
        ]
    )


def format_a2dp(summary: DeviceSummary) -> str:
    return "\n".join(
        [
            f"Device: {summary.name or summary.model or 'Unknown'}",
            f"Classic MAC: {summary.address}",
            f"Active Audio Device: {'Yes' if summary.is_active_audio_device else 'No'}",
            f"Connection State: {summary.connection_state or '-'}",
            f"Playing: {summary.is_playing or '-'}",
            f"Current Codec: {summary.current_codec or '-'}",
            f"Optional Codec Support: {summary.optional_codec_support or '-'}",
            f"Optional Codec Enabled: {summary.optional_codec_enabled or '-'}",
            f"Selectable Codecs: {', '.join(summary.selectable_codecs) if summary.selectable_codecs else '-'}",
            f"Local Capabilities: {', '.join(summary.local_capabilities) if summary.local_capabilities else '-'}",
        ]
    )


def format_codec(summary: DeviceSummary) -> str:
    return "\n".join(
        [
            f"Device: {summary.name or summary.model or 'Unknown'}",
            f"Current Codec: {summary.current_codec or '-'}",
            f"Selectable Codecs: {', '.join(summary.selectable_codecs) if summary.selectable_codecs else '-'}",
            f"Local Capabilities: {', '.join(summary.local_capabilities) if summary.local_capabilities else '-'}",
            f"Optional Codec Support: {summary.optional_codec_support or '-'}",
            f"Optional Codec Enabled: {summary.optional_codec_enabled or '-'}",
        ]
    )


def summary_to_json(summary: DeviceSummary) -> str:
    return json.dumps(asdict(summary), indent=2, ensure_ascii=False)


def list_summaries(
    summaries: dict[str, DeviceSummary],
    bonded: dict[str, tuple[str | None, str | None]],
    active_addresses: set[str],
) -> list[dict[str, object]]:
    choose_target(summaries, bonded, active_addresses, None)
    items = []
    for summary in summaries.values():
        label = summary.name or summary.model or summary.address
        if summary.is_active_audio_device:
            label = f"{label} [Active]"
        items.append(
            {
                "label": label,
                "address": summary.address,
                "name": summary.name,
                "model": summary.model,
                "is_active_audio_device": summary.is_active_audio_device,
            }
        )
    items.sort(
        key=lambda item: (
            not bool(item["is_active_audio_device"]),
            not bool(item["name"] or item["model"]),
            str(item["label"]).lower(),
        )
    )
    return items


def main() -> int:
    parser = argparse.ArgumentParser(description="Summarize Bluetooth audio device info from adb dumpsys bluetooth_manager.")
    parser.add_argument(
        "--device",
        help="Optional device name, model, or MAC substring. Defaults to the current active audio device.",
    )
    parser.add_argument("--json", action="store_true", help="Emit JSON instead of text.")
    parser.add_argument("--list-json", action="store_true", help="Emit a JSON list of available devices.")
    parser.add_argument("--codec-only", action="store_true", help="Show only codec-related A2DP fields.")
    parser.add_argument("--metadata-only", action="store_true", help="Show only device metadata fields.")
    parser.add_argument("--a2dp-only", action="store_true", help="Show only A2DP-related fields.")
    args = parser.parse_args()

    text_modes = sum(bool(flag) for flag in [args.codec_only, args.metadata_only, args.a2dp_only])
    if text_modes > 1:
        sys.stderr.write("Choose at most one of --codec-only, --metadata-only, or --a2dp-only.\n")
        return 2

    try:
        text = run_adb_dumpsys()
    except subprocess.CalledProcessError as exc:
        sys.stderr.write(exc.stderr or str(exc))
        return exc.returncode or 1
    except FileNotFoundError:
        sys.stderr.write("adb not found. Install Android platform-tools and ensure adb is on PATH.\n")
        return 1

    bonded = parse_bonded_devices(text)
    summaries = parse_metadata_blocks(text)
    parse_a2dp_state(text, summaries)
    active_addresses = parse_active_addresses(text)
    if args.list_json:
        print(json.dumps(list_summaries(summaries, bonded, active_addresses), indent=2, ensure_ascii=False))
        return 0
    target = choose_target(summaries, bonded, active_addresses, args.device)
    if not target:
        sys.stderr.write("No matching Bluetooth device found in dumpsys output.\n")
        return 1

    if args.json:
        print(summary_to_json(target))
    elif args.codec_only:
        print(format_codec(target))
    elif args.metadata_only:
        print(format_metadata(target))
    elif args.a2dp_only:
        print(format_a2dp(target))
    else:
        print(format_summary(target))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
