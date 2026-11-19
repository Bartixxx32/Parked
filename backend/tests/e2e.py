#!/usr/bin/env python3
"""End-to-end check of the deployed Parked family API.

Creates temporary groups/devices, verifies create/join/spot upsert/list/
rename/rotate/leave/delete semantics, then removes all test data.
"""

from __future__ import annotations

import json
import math
import os
import re
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone

BASE = os.environ.get("PARKED_E2E_BASE", "https://parked.bartixxx.workers.dev")
TIMEOUT = 30
CODE_RE = re.compile(r"^[0-9A-HJKMNP-TV-Z]{10}$")

passed = 0
failed = 0
created_devices: list[tuple[str, str]] = []  # (deviceToken, label)


def check(label: str, condition: bool, detail: str = "") -> None:
    global passed, failed
    if condition:
        passed += 1
        print(f"PASS  {label}")
    else:
        failed += 1
        print(f"FAIL  {label}" + (f" — {detail}" if detail else ""))


def request(method: str, path: str, body=None, token=None, admin=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Accept", "application/json")
    req.add_header("User-Agent", "Parked-e2e/1.0")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    if admin:
        req.add_header("X-Admin-Token", admin)
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            raw = resp.read()
            return resp.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            parsed = json.loads(raw) if raw else None
        except json.JSONDecodeError:
            parsed = raw.decode(errors="replace")
        return e.code, parsed


def response_headers(method: str, path: str):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Accept", "application/json")
    req.add_header("User-Agent", "Parked-e2e/1.0")
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            return resp.status, {k.lower(): v for k, v in resp.headers.items()}
    except urllib.error.HTTPError as e:
        return e.code, {k.lower(): v for k, v in e.headers.items()}


def parse_time(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def close(a: float, b: float, eps: float = 1e-6) -> bool:
    return math.isclose(a, b, abs_tol=eps)


def member_map(payload):
    return {m["deviceId"]: m for m in payload.get("members", [])}


def main() -> int:
    stamp = datetime.now(timezone.utc).strftime("%H%M%S")
    code = None
    group_id = None
    a = b = c = None
    new_code = None

    try:
        status, health = request("GET", "/v1/health")
        check("health endpoint", status == 200 and health.get("ok") is True, f"{status} {health}")

        status, headers = response_headers("GET", "/v1/health")
        check("health has no wildcard CORS", status == 200 and "access-control-allow-origin" not in headers, repr(headers))
        check(
            "health has security headers",
            headers.get("x-content-type-options") == "nosniff"
            and headers.get("cache-control") == "no-store",
            repr(headers),
        )
        status, _ = request("OPTIONS", "/v1/groups")
        check("browser preflight is not supported", status == 404, f"{status}")

        status, body = request("POST", "/v1/groups", {"displayName": "x" * 5000})
        check("oversized body is rejected", status == 413 and body == {"error": "body_too_large"}, f"{status} {body}")

        # --- create ---
        status, a = request(
            "POST",
            "/v1/groups",
            {"displayName": f"E2E Volkswagen {stamp}"},
        )
        check("create group returns 201", status == 201, f"{status} {a}")
        check(
            "create returns all credentials",
            isinstance(a, dict)
            and all(a.get(k) for k in ("groupId", "deviceId", "deviceToken", "adminToken", "inviteCode")),
            repr(a),
        )
        if not isinstance(a, dict):
            return 1
        group_id = a["groupId"]
        code = a["inviteCode"]
        created_devices.append((a["deviceToken"], "A"))
        check("invite code has 10 Crockford chars", bool(CODE_RE.match(code)), code)

        status, payload = request("GET", "/v1/spots", token=a["deviceToken"])
        check("list spots with token", status == 200, f"{status} {payload}")
        members = member_map(payload or {})
        check("new group has exactly one member", len(members) == 1, repr(members))
        me = members.get(a["deviceId"])
        check("creator has no spot yet", me is not None and me.get("spot") is None, repr(me))
        check("creator keeps car label", me and me.get("displayName") == f"E2E Volkswagen {stamp}", repr(me))
        check("listed group id matches", (payload or {}).get("groupId") == group_id, repr(payload))

        saved_a1 = "2026-11-18T18:00:00+00:00"
        status, body = request(
            "PUT",
            "/v1/spot",
            {"latitude": 52.2297, "longitude": 21.0122, "accuracyM": 8.5, "savedAt": saved_a1},
            token=a["deviceToken"],
        )
        check("upload first spot", status == 204, f"{status} {body}")

        status, payload = request("GET", "/v1/spots", token=a["deviceToken"])
        me = member_map(payload or {}).get(a["deviceId"], {})
        spot = me.get("spot") or {}
        check("first spot is visible", status == 200 and spot, f"{status} {payload}")
        check("first spot coordinates", close(spot.get("latitude", 0), 52.2297) and close(spot.get("longitude", 0), 21.0122), repr(spot))
        check("first spot accuracy", spot.get("accuracyM") == 8.5, repr(spot))
        check("first spot savedAt", parse_time(spot["savedAt"]) == parse_time(saved_a1), repr(spot))
        check("first spot updates lastSeenAt", bool(me.get("lastSeenAt")), repr(me))

        # --- join second member ---
        status, b = request(
            "POST",
            "/v1/groups/join",
            {"inviteCode": code, "displayName": f"E2E Opel {stamp}"},
        )
        check("join with invite code", status == 200 and b and b.get("groupId") == group_id, f"{status} {b}")
        if not b:
            return 1
        created_devices.append((b["deviceToken"], "B"))
        check("joined device has no admin token", not b.get("adminToken"), repr(b))

        status, body = request(
            "PUT",
            "/v1/spot",
            {"latitude": 50.0647, "longitude": 19.9450, "accuracyM": 14, "savedAt": "2026-11-18T18:05:00+00:00"},
            token=b["deviceToken"],
        )
        check("second member uploads spot", status == 204, f"{status} {body}")

        status, payload = request("GET", "/v1/spots", token=b["deviceToken"])
        members = member_map(payload or {})
        check("both members are listed", status == 200 and len(members) == 2, f"{status} {members}")
        check(
            "each member has their own spot",
            members.get(a["deviceId"], {}).get("spot", {}).get("accuracyM") == 8.5
            and members.get(b["deviceId"], {}).get("spot", {}).get("accuracyM") == 14,
            repr(members),
        )

        # --- groups cannot read each other's spots ---
        status, other = request(
            "POST",
            "/v1/groups",
            {"displayName": f"E2E Other {stamp}"},
        )
        check("second group created", status == 201 and bool(other), f"{status} {other}")
        if other:
            created_devices.append((other["deviceToken"], "other"))
            status, other_payload = request("GET", "/v1/spots", token=other["deviceToken"])
            other_members = member_map(other_payload or {})
            check(
                "groups are isolated",
                status == 200
                and len(other_members) == 1
                and other["deviceId"] in other_members
                and a["deviceId"] not in other_members,
                repr(other_members),
            )
            request("DELETE", "/v1/device", token=other["deviceToken"])
            created_devices.remove((other["deviceToken"], "other"))

        # --- replace only the latest spot ---
        status, body = request(
            "PUT",
            "/v1/spot",
            {"latitude": 52.2320, "longitude": 21.0150, "accuracyM": 6, "savedAt": "2026-11-18T18:10:00+00:00"},
            token=a["deviceToken"],
        )
        check("second upload for same device", status == 204, f"{status} {body}")

        status, payload = request("GET", "/v1/spots", token=b["deviceToken"])
        members = member_map(payload or {})
        a_spot = members.get(a["deviceId"], {}).get("spot") or {}
        check("newer spot replaces older spot", close(a_spot.get("latitude", 0), 52.2320) and a_spot.get("accuracyM") == 6, repr(a_spot))
        check("other member's spot is unchanged", members.get(b["deviceId"], {}).get("spot", {}).get("accuracyM") == 14, repr(members))
        check("still one spot per device", len(members) == 2, repr(members))

        # --- rename to a registration plate ---
        new_name = "WA 12345"
        status, body = request("PATCH", "/v1/device", {"displayName": new_name}, token=a["deviceToken"])
        check("change car label", status == 204, f"{status} {body}")
        status, payload = request("GET", "/v1/spots", token=b["deviceToken"])
        check("renamed car is visible to others", member_map(payload or {}).get(a["deviceId"], {}).get("displayName") == new_name, repr(payload))

        # --- permissions ---
        status, body = request("POST", "/v1/groups/rotate", token=b["deviceToken"])
        check("non-admin cannot rotate code", status == 403 and body == {"error": "forbidden"}, f"{status} {body}")
        status, body = request("POST", "/v1/groups/rotate", token=a["deviceToken"], admin="wrong-token")
        check("wrong admin token cannot rotate code", status == 403, f"{status} {body}")
        status, body = request("GET", "/v1/spots")
        check("missing token is rejected", status == 401, f"{status} {body}")

        # --- rotate invite code ---
        status, body = request("POST", "/v1/groups/rotate", token=a["deviceToken"], admin=a["adminToken"])
        check("admin can rotate code", status == 200 and body and code != body.get("inviteCode"), f"{status} {body}")
        new_code = (body or {}).get("inviteCode")
        check("rotated code is valid Crockford", bool(new_code and CODE_RE.match(new_code)), repr(new_code))

        status, _ = request("POST", "/v1/groups/join", {"inviteCode": code, "displayName": f"E2E Old {stamp}"})
        check("old invite code is rejected", status == 404, f"{status}")

        status, c = request(
            "POST",
            "/v1/groups/join",
            {"inviteCode": new_code, "displayName": f"E2E Toyota {stamp}"},
        )
        check("new invite code works", status == 200 and c and c.get("groupId") == group_id, f"{status} {c}")
        if not c:
            return 1
        created_devices.append((c["deviceToken"], "C"))

        # --- validation ---
        status, body = request("PUT", "/v1/spot", {"latitude": 91, "longitude": 0}, token=a["deviceToken"])
        check("out-of-range latitude is rejected", status == 400 and body == {"error": "invalid_coordinates"}, f"{status} {body}")
        status, body = request("PUT", "/v1/spot", {"latitude": 0, "longitude": 0, "accuracyM": 10001}, token=a["deviceToken"])
        check("excessive accuracy is rejected", status == 400 and body == {"error": "invalid_accuracy"}, f"{status} {body}")
        status, body = request("POST", "/v1/groups", {"displayName": "   "})
        check("blank car label is rejected", status == 400 and body == {"error": "invalid_name"}, f"{status} {body}")

        # --- leaving removes the member and their spot ---
        status, body = request("DELETE", "/v1/device", token=b["deviceToken"])
        check("member leaves group", status == 204, f"{status} {body}")
        created_devices.remove((b["deviceToken"], "B"))

        status, payload = request("GET", "/v1/spots", token=a["deviceToken"])
        members = member_map(payload or {})
        check("left member disappears", status == 200 and b["deviceId"] not in members, repr(members))
        check("remaining members stay", len(members) == 2 and a["deviceId"] in members and c["deviceId"] in members, repr(members))

        # --- last member deletes the group ---
        status, body = request("DELETE", "/v1/device", token=c["deviceToken"])
        check("second-to-last member leaves", status == 204, f"{status} {body}")
        created_devices.remove((c["deviceToken"], "C"))

        status, body = request("DELETE", "/v1/device", token=a["deviceToken"])
        check("last member leaves", status == 204, f"{status} {body}")
        created_devices.remove((a["deviceToken"], "A"))

        status, body = request("GET", "/v1/spots", token=a["deviceToken"])
        check("deleted group token is rejected", status == 401, f"{status} {body}")
        status, _ = request("POST", "/v1/groups/join", {"inviteCode": new_code, "displayName": f"E2E Late {stamp}"})
        check("deleted group invite code is rejected", status == 404, f"{status}")

        return 0 if failed == 0 else 1
    finally:
        # Best-effort cleanup if an assertion aborted the flow.
        for token, label in list(created_devices):
            try:
                request("DELETE", "/v1/device", token=token)
                print(f"cleanup: removed device {label}")
            except Exception as exc:
                print(f"cleanup: failed to remove device {label}: {exc}")


if __name__ == "__main__":
    sys.exit(main())
