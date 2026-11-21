#!/usr/bin/env python3
"""Capture F-Droid screenshots from a running Android emulator.

The script drives the real app (no fake UI): it adds three manual spots through
the History dialog, then captures every main screen in each app language.
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
RES_DIR = Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "res"

# F-Droid store locale -> Android resource qualifier. Portuguese has two store
# listings but a single translation in the app, so both listings reuse it.
LOCALES = [
    ("en-US", "values"),
    ("pl-PL", "values-pl"),
    ("de-DE", "values-de"),
    ("fr-FR", "values-fr"),
    ("es-ES", "values-es"),
    ("it-IT", "values-it"),
    ("pt-PT", "values-pt"),
    ("pt-BR", "values-pt"),
    ("ru-RU", "values-ru"),
    ("uk-UA", "values-uk"),
    ("zh-CN", "values-zh-rCN"),
    ("nl-NL", "values-nl"),
    ("cs-CZ", "values-cs"),
]

LABEL_KEYS = (
    "add_manual_spot",
    "label_latitude",
    "label_longitude",
    "label_note_optional",
    "action_add",
    "map_picker_pick",
    "map_picker_use",
    "action_cancel",
)

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


def node_center(root: ET.Element, text: str) -> tuple[int, int] | None:
    for node in root.iter("node"):
        if node.attrib.get("text") == text or node.attrib.get("content-desc") == text:
            numbers = [int(v) for v in re.findall(r"\d+", node.attrib.get("bounds", ""))]
            if len(numbers) == 4:
                x1, y1, x2, y2 = numbers
                return (x1 + x2) // 2, (y1 + y2) // 2
    return None


def find_text(text: str, timeout: float = 20.0) -> tuple[int, int] | None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            root = dump_ui()
        except RuntimeError:
            time.sleep(1)
            continue
        position = node_center(root, text)
        if position is not None:
            return position
        time.sleep(1)
    return None


def app_visible() -> bool:
    try:
        root = dump_ui()
    except RuntimeError:
        return False
    return any(node.attrib.get("package") == PKG for node in root.iter("node"))


def dismiss_system_dialog() -> bool:
    """Dismiss a launcher ANR dialog that can cover the app on a slow emulator."""
    try:
        root = dump_ui()
    except RuntimeError:
        return False
    texts = [node.attrib.get("text", "") for node in root.iter("node")]
    if not any("responding" in text for text in texts):
        return False
    for label in ("Wait", "Close app"):
        position = node_center(root, label)
        if position is not None:
            print(f"dismissing system dialog with {label!r}", flush=True)
            tap(*position)
            time.sleep(2)
            return True
    return False


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


def screenshot(directory: Path, name: str) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    if dismiss_system_dialog():
        time.sleep(1)
    (directory / name).write_bytes(adb("exec-out", "screencap", "-p", binary=True))
    print(f"captured {directory.name}/{name}", flush=True)


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
    time.sleep(2)
    deadline = time.time() + 45
    while time.time() < deadline:
        if app_visible():
            break
        if dismiss_system_dialog():
            continue
        time.sleep(1)
    else:
        save_diagnostics("app-not-visible")
        raise RuntimeError("the app window did not become visible after launch")
    # Give Compose one more moment to lay out the bottom navigation bar.
    time.sleep(3)
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


def android_text(value: str) -> str:
    """Turn Android string escaping into the text Compose renders."""
    value = re.sub(r"\\(['\"\\])", r"\1", value)
    return value.replace("\\n", "\n")


def load_labels(qualifier: str) -> dict[str, str]:
    path = RES_DIR / qualifier / "strings.xml"
    root = ET.parse(path).getroot()
    values = {
        node.get("name"): android_text((node.text or "").strip())
        for node in root.findall("string")
    }
    missing = [key for key in LABEL_KEYS if not values.get(key)]
    if missing:
        raise RuntimeError(f"{path}: missing strings: {', '.join(missing)}")
    return {key: values[key] for key in LABEL_KEYS}


def open_history(labels: dict[str, str]) -> None:
    """Switch to History, retrying if the first tap lands before layout."""
    for attempt in range(3):
        tap_tab(1)
        if find_text(labels["add_manual_spot"], timeout=15) is not None:
            return
        print("history is not ready, retrying", flush=True)
        if dismiss_system_dialog():
            time.sleep(2)
    print("history is still not ready, restarting the app", flush=True)
    shell(f"am force-stop {PKG}")
    time.sleep(1)
    launch()
    tap_tab(1)
    tap_text(labels["add_manual_spot"], timeout=20)


def add_spots(labels: dict[str, str]) -> None:
    open_history(labels)
    for latitude, longitude, note in SPOTS:
        tap_text(labels["add_manual_spot"])
        tap_text(labels["label_latitude"])
        type_text(latitude)
        hide_keyboard()
        tap_text(labels["label_longitude"])
        type_text(longitude)
        hide_keyboard()
        if note:
            tap_text(labels["label_note_optional"])
            type_text(note)
            hide_keyboard()
        tap_text(labels["action_add"])
        time.sleep(1.5)


def capture_map_picker(labels: dict[str, str], directory: Path) -> None:
    """Capture the manual add dialog and its full-screen map picker."""
    open_history(labels)
    time.sleep(1)
    tap_text(labels["add_manual_spot"])
    tap_text(labels["map_picker_pick"])
    time.sleep(12)  # let OSM tiles load
    screenshot(directory, "6.png")
    tap_text(labels["map_picker_use"])
    time.sleep(1)
    tap_text(labels["action_cancel"])
    time.sleep(1)


def capture_locale(locale: str, labels: dict[str, str]) -> None:
    directory = OUTDIR / locale
    directory.mkdir(parents=True, exist_ok=True)
    for old in directory.glob("*.png"):
        old.unlink()

    set_locale(locale)
    tap_tab(0)
    time.sleep(1)
    screenshot(directory, "1.png")
    tap_tab(1)
    time.sleep(1)
    screenshot(directory, "2.png")
    tap_tab(2)
    time.sleep(12)  # let OSM tiles load
    screenshot(directory, "3.png")
    tap_tab(3)
    time.sleep(1)
    screenshot(directory, "4.png")
    tap_tab(4)
    time.sleep(1)
    screenshot(directory, "5.png")
    capture_map_picker(labels, directory)


def selected_locales() -> list[tuple[str, str]]:
    requested = {
        item.strip()
        for item in os.environ.get("SCREENSHOT_LOCALES", "").split(",")
        if item.strip()
    }
    if not requested:
        return LOCALES
    known = {locale for locale, _ in LOCALES}
    unknown = requested - known
    if unknown:
        raise RuntimeError(f"unknown screenshot locales: {', '.join(sorted(unknown))}")
    return [entry for entry in LOCALES if entry[0] in requested]


def main() -> int:
    try:
        return run()
    except Exception:
        save_diagnostics("fatal")
        raise


def run() -> int:
    # Prepare the device.
    shell("settings put secure show_ime_with_hard_keyboard 1")
    # A launcher ANR must not cover the app while screens are captured.
    shell("settings put global hide_error_dialogs 1")
    shell("settings put secure anr_show_background 0")
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
    add_spots(load_labels("values"))

    for locale, qualifier in selected_locales():
        capture_locale(locale, load_labels(qualifier))

    print("screenshots ready", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
