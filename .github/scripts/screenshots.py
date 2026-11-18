#!/usr/bin/env python3
"""Capture F-Droid screenshots from a running Android emulator.

The script drives the real app (no fake UI): it adds three manual spots through
the History dialog, then captures every main screen in English and Polish.
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PKG = "com.bartixxx.parked.debug"
ACTIVITY = f"{PKG}/com.bartixxx.parked.MainActivity"
OUTDIR = Path(os.environ.get("SCREENSHOT_OUT", "screenshots"))

# Warsaw area, so the map screenshot shows a real city.
SPOTS = [
    ("52.22977", "21.01178", "Level 2"),
    ("52.23220", "21.00600", "Blue zone"),
    ("52.22550", "21.01600", ""),
]


def adb(*args: str, binary: bool = False):
    result = subprocess.run(["adb", *args], capture_output=True)
    if result.returncode != 0:
        stderr = result.stderr if binary else result.stderr.decode(errors="replace")
        raise RuntimeError(f"adb {' '.join(args)} failed: {stderr}")
    return result.stdout if binary else result.stdout.decode(errors="replace")


def shell(command: str) -> str:
    return adb("shell", command)


def tap(x: int, y: int) -> None:
    shell(f"input tap {x} {y}")


def dump_ui() -> ET.Element:
    last_error: Exception | None = None
    for _ in range(5):
        try:
            shell("uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1")
            xml = shell("cat /sdcard/window_dump.xml")
            return ET.fromstring(xml)
        except (RuntimeError, ET.ParseError) as error:  # transient while animating
            last_error = error
            time.sleep(1)
    raise RuntimeError(f"cannot dump UI: {last_error}")


def find_text(text: str, timeout: float = 20.0) -> tuple[int, int] | None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            root = dump_ui()
        except RuntimeError:
            time.sleep(1)
            continue
        for node in root.iter("node"):
            if node.attrib.get("text") == text or node.attrib.get("content-desc") == text:
                numbers = [int(v) for v in re.findall(r"\d+", node.attrib.get("bounds", ""))]
                if len(numbers) == 4:
                    x1, y1, x2, y2 = numbers
                    return (x1 + x2) // 2, (y1 + y2) // 2
        time.sleep(1)
    return None


def tap_text(text: str, timeout: float = 20.0) -> None:
    position = find_text(text, timeout)
    if position is None:
        raise RuntimeError(f"UI element not found: {text!r}")
    tap(*position)
    time.sleep(0.8)


def type_text(value: str) -> None:
    # `input text` uses %s for spaces; our values contain no other special chars.
    shell(f"input text '{value.replace(' ', '%s')}'")
    time.sleep(0.4)


def hide_keyboard() -> None:
    shell("input keyevent 111")  # ESC closes the IME without dismissing the dialog
    time.sleep(0.4)


def screenshot(name: str) -> None:
    OUTDIR.mkdir(parents=True, exist_ok=True)
    (OUTDIR / name).write_bytes(adb("exec-out", "screencap", "-p", binary=True))
    print(f"captured {name}", flush=True)


def launch() -> None:
    shell(f"am start -W -n {ACTIVITY} >/dev/null")
    time.sleep(3)


def set_locale(locale: str) -> None:
    shell(f"cmd locale set-app-locales {PKG} --locales {locale}")
    shell(f"am force-stop {PKG}")
    time.sleep(1)
    launch()


def add_spots() -> None:
    tap_text("History")
    for latitude, longitude, note in SPOTS:
        tap_text("Add spot manually")
        tap_text("Latitude")
        type_text(latitude)
        hide_keyboard()
        tap_text("Longitude")
        type_text(longitude)
        hide_keyboard()
        if note:
            tap_text("Note (optional)")
            type_text(note)
            hide_keyboard()
        tap_text("Add")
        time.sleep(1.5)


def capture_english() -> None:
    set_locale("en-US")
    add_spots()
    tap_text("History")
    time.sleep(1)
    screenshot("2.png")
    tap_text("Dashboard")
    time.sleep(1)
    screenshot("1.png")
    tap_text("Map")
    time.sleep(12)  # let OSM tiles load
    screenshot("3.png")
    tap_text("Settings")
    time.sleep(1)
    screenshot("4.png")
    tap_text("Diagnostics")
    time.sleep(1)
    screenshot("5.png")


def capture_polish() -> None:
    set_locale("pl-PL")
    tap_text("Pulpit")
    time.sleep(1)
    screenshot("1.png")
    tap_text("Historia")
    time.sleep(1)
    screenshot("2.png")
    tap_text("Mapa")
    time.sleep(12)
    screenshot("3.png")
    tap_text("Ustawienia")
    time.sleep(1)
    screenshot("4.png")
    tap_text("Diagnostyka")
    time.sleep(1)
    screenshot("5.png")


def main() -> int:
    # Prepare the device.
    shell("settings put secure show_ime_with_hard_keyboard 1")
    for permission in (
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.POST_NOTIFICATIONS",
    ):
        subprocess.run(
            ["adb", "shell", "pm", "grant", PKG, permission],
            capture_output=True,
        )

    subprocess.run(
        ["adb", "emu", "geo", "fix", "21.01178", "52.22977"],
        capture_output=True,
    )

    launch()
    capture_english()

    english_dir = OUTDIR / "en-US"
    polish_dir = OUTDIR / "pl-PL"
    english_dir.mkdir(parents=True, exist_ok=True)
    for item in OUTDIR.glob("*.png"):
        item.replace(english_dir / item.name)

    capture_polish()
    polish_dir.mkdir(parents=True, exist_ok=True)
    for item in OUTDIR.glob("*.png"):
        item.replace(polish_dir / item.name)

    print("screenshots ready", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
