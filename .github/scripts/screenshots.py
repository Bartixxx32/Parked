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

PKG = os.environ.get("PARKED_PKG", "com.bartixxx.parked.debug")
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
        label = re.sub(r"\W+", "-", text).strip("-").lower() or "element"
        save_diagnostics(f"missing-{label}")
        raise RuntimeError(f"UI element not found: {text!r}")
    tap(*position)
    time.sleep(0.8)


def type_text(value: str) -> None:
    # `input text` uses %s for spaces; our values contain no other special chars.
    shell(f"input text '{value.replace(' ', '%s')}'")
    time.sleep(0.4)


def hide_keyboard() -> None:
    """Hide the IME without dismissing the Compose dialog.

    BACK only hides the IME while it is visible; if it is not visible it would
    close the dialog, so check the IME state first.
    """
    state = shell("dumpsys input_method")
    if "mInputShown=true" in state or "mIsInputViewShown=true" in state:
        shell("input keyevent 4")
        time.sleep(0.6)


def screenshot(name: str) -> None:
    OUTDIR.mkdir(parents=True, exist_ok=True)
    (OUTDIR / name).write_bytes(adb("exec-out", "screencap", "-p", binary=True))
    print(f"captured {name}", flush=True)


def save_diagnostics(label: str) -> None:
    """Save enough context to debug a failed run from the CI artifact."""
    OUTDIR.mkdir(parents=True, exist_ok=True)
    try:
        (OUTDIR / f"debug-{label}.png").write_bytes(
            adb("exec-out", "screencap", "-p", binary=True)
        )
    except Exception as error:  # noqa: BLE001 - diagnostics must never mask the real error
        print(f"cannot save screenshot: {error}", flush=True)
    for name, command in (
        ("ui.xml", "cat /sdcard/window_dump.xml"),
        ("logcat.txt", "logcat -d -t 1500"),
        ("activity.txt", "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'"),
        ("focus.txt", "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"),
        ("process.txt", f"pidof {PKG}"),
    ):
        try:
            (OUTDIR / f"debug-{label}-{name}").write_text(shell(command))
        except Exception as error:  # noqa: BLE001
            print(f"cannot save {name}: {error}", flush=True)


def tap_tab(index: int) -> None:
    """Tap one of the five bottom navigation items.

    Coordinate taps keep working even when uiautomator does not expose Compose
    labels, which differs between Android versions.
    """
    size = shell("wm size")
    match = re.search(r"(\d+)x(\d+)", size)
    if not match:
        raise RuntimeError(f"cannot read screen size: {size!r}")
    width, height = int(match.group(1)), int(match.group(2))
    x = int(width * (index + 0.5) / 5)
    y = height - 140  # inside the navigation bar above the gesture inset
    tap(x, y)
    time.sleep(1)


def launch() -> None:
    shell(f"am start -W -n {ACTIVITY} >/dev/null")
    time.sleep(4)
    pid = shell(f"pidof {PKG}").strip()
    if not pid:
        save_diagnostics("not-running")
        raise RuntimeError("the app process is not running after launch")
    print(f"app pid: {pid}", flush=True)


def set_locale(locale: str) -> None:
    shell(f"cmd locale set-app-locales {PKG} --locales {locale}")
    shell(f"am force-stop {PKG}")
    time.sleep(1)
    launch()


def add_spots() -> None:
    tap_tab(1)
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
    tap_tab(1)
    time.sleep(1)
    screenshot("2.png")
    tap_tab(0)
    time.sleep(1)
    screenshot("1.png")
    tap_tab(2)
    time.sleep(12)  # let OSM tiles load
    screenshot("3.png")
    tap_tab(3)
    time.sleep(1)
    screenshot("4.png")
    tap_tab(4)
    time.sleep(1)
    screenshot("5.png")


def capture_polish() -> None:
    set_locale("pl-PL")
    tap_tab(0)
    time.sleep(1)
    screenshot("1.png")
    tap_tab(1)
    time.sleep(1)
    screenshot("2.png")
    tap_tab(2)
    time.sleep(12)
    screenshot("3.png")
    tap_tab(3)
    time.sleep(1)
    screenshot("4.png")
    tap_tab(4)
    time.sleep(1)
    screenshot("5.png")


def main() -> int:
    try:
        return run()
    except Exception:
        save_diagnostics("fatal")
        raise


def run() -> int:
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
