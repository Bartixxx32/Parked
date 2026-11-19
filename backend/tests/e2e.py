#!/usr/bin/env python3
"""End-to-end check of the deployed Parked family API.

Covers the encrypted API (default for current clients), the legacy plaintext
API kept for 1.2.0/1.3.0 compatibility, validation and cleanup. Requires the
`cryptography` package for AES-GCM.

Usage: python3 backend/tests/e2e.py
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import math
import os
import re
import secrets
import struct
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

BASE = os.environ.get("PARKED_E2E_BASE", "https://parked.bartixxx.workers.dev")
TIMEOUT = 30
CODE_RE = re.compile(r"^[0-9A-HJKMNP-TV-Z]{10}$")
KEY_VERSION = 1
PAYLOAD_BYTES = 384
IV_BYTES = 12
WRAP_AAD = b"parked-key-wrap|v1"

passed = 0
failed = 0


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
    req.add_header("User-Agent", "Parked-e2e/2.0")
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
    req.add_header("User-Agent", "Parked-e2e/2.0")
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            return resp.status, {k.lower(): v for k, v in resp.headers.items()}
    except urllib.error.HTTPError as e:
        return e.code, {k.lower(): v for k, v in e.headers.items()}


def parse_time(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def close(a: float, b: float, eps: float = 1e-6) -> bool:
    return math.isclose(a, b, abs_tol=eps)


def member_map(payload) -> dict:
    return {m["deviceId"]: m for m in (payload or {}).get("members", [])}


def iso(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000, tz=timezone.utc).isoformat()


def b64e(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode()


def b64d(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def hkdf(ikm: bytes, salt: bytes, info: bytes, length: int) -> bytes:
    prk = hmac.new(salt or b"\x00" * 32, ikm, hashlib.sha256).digest()
    output = b""
    previous = b""
    counter = 1
    while len(output) < length:
        previous = hmac.new(prk, previous + info + bytes([counter]), hashlib.sha256).digest()
        output += previous
        counter += 1
    return output[:length]


def join_auth_key(secret: str) -> str:
    return b64e(hkdf(b64d(secret), b"parked-join-auth-v1", b"join-auth|v1", 32))


def wrap_group_key(group_key: bytes, secret: str) -> dict:
    salt = secrets.token_bytes(16)
    iv = secrets.token_bytes(12)
    wrapping_key = hkdf(b64d(secret), salt, b"key-wrap|v1", 32)
    ciphertext = AESGCM(wrapping_key).encrypt(iv, group_key, WRAP_AAD)
    return {
        "keySalt": b64e(salt),
        "keyIv": b64e(iv),
        "wrappedKey": b64e(ciphertext),
        "joinAuthHash": join_auth_key(secret),
    }


def unwrap_group_key(secret: str, crypto: dict) -> bytes:
    salt = b64d(crypto["keySalt"])
    iv = b64d(crypto["keyIv"])
    wrapping_key = hkdf(b64d(secret), salt, b"key-wrap|v1", 32)
    return AESGCM(wrapping_key).decrypt(iv, b64d(crypto["wrappedKey"]), WRAP_AAD)


def encrypt_spot(
    group_key: bytes,
    group_id: str,
    device_id: str,
    label: str,
    latitude: float,
    longitude: float,
    accuracy: float | None,
    saved_at_ms: int,
) -> str:
    label_bytes = label.encode()
    plain = bytearray(PAYLOAD_BYTES)
    plain[0:4] = b"PKSP"
    plain[4] = 1
    plain[5] = 1 if accuracy is not None else 0
    struct.pack_into(">H", plain, 6, len(label_bytes))
    offset = 8
    plain[offset : offset + len(label_bytes)] = label_bytes
    offset += len(label_bytes)
    struct.pack_into(">ddd", plain, offset, latitude, longitude, accuracy or 0.0)
    struct.pack_into(">q", plain, offset + 24, saved_at_ms)

    key = hkdf(
        group_key,
        b"parked-spot-salt-v1",
        f"spot|{group_id}|{device_id}|{KEY_VERSION}".encode(),
        32,
    )
    aad = f"parked-spot|v1|{group_id}|{device_id}|{KEY_VERSION}".encode()
    iv = secrets.token_bytes(IV_BYTES)
    return b64e(iv + AESGCM(key).encrypt(iv, bytes(plain), aad))


def decrypt_spot(
    group_key: bytes,
    group_id: str,
    device_id: str,
    payload: str,
) -> dict:
    combined = b64d(payload)
    iv = combined[:IV_BYTES]
    key = hkdf(
        group_key,
        b"parked-spot-salt-v1",
        f"spot|{group_id}|{device_id}|{KEY_VERSION}".encode(),
        32,
    )
    aad = f"parked-spot|v1|{group_id}|{device_id}|{KEY_VERSION}".encode()
    plain = AESGCM(key).decrypt(iv, combined[IV_BYTES:], aad)
    label_length = struct.unpack_from(">H", plain, 6)[0]
    offset = 8 + label_length
    latitude, longitude, accuracy = struct.unpack_from(">ddd", plain, offset)
    saved_at_ms = struct.unpack_from(">q", plain, offset + 24)[0]
    return {
        "label": plain[8:offset].decode(),
        "latitude": latitude,
        "longitude": longitude,
        "accuracy": accuracy if plain[5] & 1 else None,
        "savedAtMs": saved_at_ms,
    }


def main() -> int:
    stamp = datetime.now(timezone.utc).strftime("%H%M%S")
    created: list[tuple[str, str]] = []

    try:
        status, health = request("GET", "/v1/health")
        check("health endpoint", status == 200 and health.get("ok") is True, f"{status} {health}")

        status, headers = response_headers("GET", "/v1/health")
        check(
            "health has no wildcard CORS",
            status == 200 and "access-control-allow-origin" not in headers,
            repr(headers),
        )
        check(
            "health has security headers",
            headers.get("x-content-type-options") == "nosniff"
            and headers.get("cache-control") == "no-store",
            repr(headers),
        )
        status, _ = request("OPTIONS", "/v1/groups")
        check("browser preflight is not supported", status == 404, f"{status}")

        status, body = request("POST", "/v1/groups", {"displayName": "x" * 5000})
        check(
            "oversized body is rejected",
            status == 413 and body == {"error": "body_too_large"},
            f"{status} {body}",
        )

        # --- encrypted group ---
        group_key = secrets.token_bytes(32)
        secret = b64e(secrets.token_bytes(16))
        status, a = request(
            "POST",
            "/v1/groups",
            {
                "displayName": f"E2E Volkswagen {stamp}",
                "crypto": wrap_group_key(group_key, secret),
            },
        )
        check("encrypted create returns 201", status == 201, f"{status} {a}")
        check(
            "encrypted create returns all credentials",
            isinstance(a, dict)
            and all(
                a.get(k)
                for k in ("groupId", "deviceId", "deviceToken", "adminToken", "inviteCode")
            ),
            repr(a),
        )
        if not isinstance(a, dict):
            return 1
        created.append((a["deviceToken"], "A"))
        group_id = a["groupId"]
        code = a["inviteCode"]
        check("invite code has 10 Crockford chars", bool(CODE_RE.match(code)), code)
        check(
            "encrypted create returns key material",
            a.get("crypto", {}).get("mode") == "e2ee"
            and a["crypto"].get("keyVersion") == 1,
            repr(a.get("crypto")),
        )
        check(
            "server key material unwraps to the same group key",
            unwrap_group_key(secret, a["crypto"]) == group_key,
            "key mismatch",
        )

        status, payload = request("GET", "/v1/spots", token=a["deviceToken"])
        members = member_map(payload)
        me = members.get(a["deviceId"], {})
        check("encrypted list works", status == 200 and len(members) == 1, f"{status} {payload}")
        check("encrypted group reports its mode", (payload or {}).get("cryptoMode") == "e2ee", repr(payload))
        check(
            "server does not return a plaintext label",
            me is not None and "displayName" not in me,
            repr(me),
        )

        saved_a = 1_700_000_000_000
        status, body = request(
            "PUT",
            "/v1/spot",
            {
                "payload": encrypt_spot(
                    group_key,
                    group_id,
                    a["deviceId"],
                    "WA 12345",
                    52.2297,
                    21.0122,
                    8.5,
                    saved_a,
                ),
                "keyVersion": 1,
                "savedAt": iso(saved_a),
            },
            token=a["deviceToken"],
        )
        check("encrypted spot upload", status == 204, f"{status} {body}")

        status, payload = request("GET", "/v1/spots", token=a["deviceToken"])
        me = member_map(payload).get(a["deviceId"], {})
        spot = me.get("spot") or {}
        check(
            "server stores no plaintext coordinates",
            status == 200 and payload
            and "latitude" not in spot
            and "longitude" not in spot
            and "accuracyM" not in spot,
            repr(spot),
        )
        decrypted = decrypt_spot(group_key, group_id, a["deviceId"], spot.get("payload", ""))
        check(
            "encrypted spot decrypts with the group key",
            decrypted["label"] == "WA 12345"
            and close(decrypted["latitude"], 52.2297)
            and close(decrypted["longitude"], 21.0122)
            and decrypted["accuracy"] == 8.5
            and decrypted["savedAtMs"] == saved_a,
            repr(decrypted),
        )
        check(
            "ciphertext does not contain the label or coordinates",
            "WA 12345" not in json.dumps(payload) and "52.2297" not in json.dumps(payload),
            "plaintext leaked in response",
        )

        # --- encrypted joins ---
        status, body = request(
            "POST",
            "/v1/groups/join",
            {"inviteCode": code, "displayName": "No link"},
        )
        check(
            "code alone cannot join an encrypted group",
            status == 403 and body == {"error": "invite_link_required"},
            f"{status} {body}",
        )
        status, body = request(
            "POST",
            "/v1/groups/join",
            {
                "inviteCode": code,
                "displayName": "Wrong link",
                "joinAuthKey": join_auth_key(b64e(secrets.token_bytes(16))),
            },
        )
        check(
            "wrong invite secret is rejected",
            status == 403 and body == {"error": "invalid_invite"},
            f"{status} {body}",
        )

        status, b = request(
            "POST",
            "/v1/groups/join",
            {
                "inviteCode": code,
                "displayName": f"E2E Opel {stamp}",
                "joinAuthKey": join_auth_key(secret),
            },
        )
        check(
            "full invite link joins the encrypted group",
            status == 200 and b and b.get("groupId") == group_id,
            f"{status} {b}",
        )
        check(
            "joining device receives key material and no admin token",
            isinstance(b, dict)
            and not b.get("adminToken")
            and unwrap_group_key(secret, b.get("crypto", {})) == group_key,
            repr(b),
        )
        if not isinstance(b, dict):
            return 1
        created.append((b["deviceToken"], "B"))

        saved_b = 1_700_000_100_000
        request(
            "PUT",
            "/v1/spot",
            {
                "payload": encrypt_spot(
                    group_key,
                    group_id,
                    b["deviceId"],
                    "Opel Astra",
                    50.0647,
                    19.9450,
                    14,
                    saved_b,
                ),
                "keyVersion": 1,
                "savedAt": iso(saved_b),
            },
            token=b["deviceToken"],
        )
        status, payload = request("GET", "/v1/spots", token=b["deviceToken"])
        members = member_map(payload)
        check("both encrypted members are listed", status == 200 and len(members) == 2, f"{status} {members}")
        second = decrypt_spot(
            group_key,
            group_id,
            b["deviceId"],
            members[b["deviceId"]]["spot"]["payload"],
        )
        check(
            "second member's encrypted label and spot decrypt",
            second["label"] == "Opel Astra" and close(second["latitude"], 50.0647),
            repr(second),
        )

        # --- encrypted validation ---
        status, body = request(
            "PUT",
            "/v1/spot",
            {"payload": "not-base64!", "keyVersion": 1},
            token=a["deviceToken"],
        )
        check(
            "junk ciphertext is rejected",
            status == 400 and body == {"error": "invalid_crypto"},
            f"{status} {body}",
        )
        status, body = request(
            "PUT",
            "/v1/spot",
            {
                "payload": encrypt_spot(
                    group_key, group_id, a["deviceId"], "Golf", 1, 1, None, saved_a
                ),
                "keyVersion": 2,
            },
            token=a["deviceToken"],
        )
        check(
            "wrong key version is rejected",
            status == 409 and body == {"error": "key_version_mismatch"},
            f"{status} {body}",
        )

        # --- encrypted rotate ---
        status, body = request("POST", "/v1/groups/rotate", token=b["deviceToken"])
        check(
            "non-admin cannot rotate an encrypted invite",
            status == 403 and body == {"error": "forbidden"},
            f"{status} {body}",
        )
        status, body = request(
            "POST", "/v1/groups/rotate", token=a["deviceToken"], admin="wrong-token"
        )
        check("wrong admin token cannot rotate", status == 403, f"{status} {body}")

        new_secret = b64e(secrets.token_bytes(16))
        status, body = request(
            "POST",
            "/v1/groups/rotate",
            {"crypto": wrap_group_key(group_key, new_secret)},
            token=a["deviceToken"],
            admin=a["adminToken"],
        )
        new_code = (body or {}).get("inviteCode")
        check(
            "admin rotates the encrypted invite",
            status == 200 and new_code and new_code != code and CODE_RE.match(new_code),
            f"{status} {body}",
        )

        status, _ = request(
            "POST",
            "/v1/groups/join",
            {
                "inviteCode": code,
                "displayName": "Old link",
                "joinAuthKey": join_auth_key(secret),
            },
        )
        check("old encrypted invite no longer works", status == 404, f"{status}")
        status, body = request(
            "POST",
            "/v1/groups/join",
            {
                "inviteCode": new_code,
                "displayName": "Old secret",
                "joinAuthKey": join_auth_key(secret),
            },
        )
        check(
            "old secret cannot join the new invite",
            status == 403 and body == {"error": "invalid_invite"},
            f"{status} {body}",
        )
        status, body = request(
            "POST",
            "/v1/groups/join",
            {"inviteCode": new_code, "displayName": "No secret"},
        )
        check(
            "new encrypted invite still requires the link",
            status == 403 and body == {"error": "invite_link_required"},
            f"{status} {body}",
        )
        status, c = request(
            "POST",
            "/v1/groups/join",
            {
                "inviteCode": new_code,
                "displayName": f"E2E Toyota {stamp}",
                "joinAuthKey": join_auth_key(new_secret),
            },
        )
        check(
            "new invite link joins with the rotated key material",
            status == 200 and c and unwrap_group_key(new_secret, c.get("crypto", {})) == group_key,
            f"{status} {c}",
        )
        if not c:
            return 1
        created.append((c["deviceToken"], "C"))

        # --- encrypted leave ---
        status, body = request("DELETE", "/v1/device", token=b["deviceToken"])
        check("encrypted member leaves", status == 204, f"{status} {body}")
        created.remove((b["deviceToken"], "B"))
        status, payload = request("GET", "/v1/spots", token=a["deviceToken"])
        members = member_map(payload)
        check(
            "left encrypted member disappears",
            status == 200 and b["deviceId"] not in members and len(members) == 2,
            repr(members),
        )

        # --- legacy plaintext group (1.2.0/1.3.0 compatibility) ---
        status, old = request(
            "POST", "/v1/groups", {"displayName": f"Legacy {stamp}"}
        )
        check("legacy create still works", status == 201 and old, f"{status} {old}")
        if not old:
            return 1
        created.append((old["deviceToken"], "legacy-a"))
        check("legacy create has no crypto field", "crypto" not in old, repr(old))
        legacy_group = old["groupId"]
        legacy_code = old["inviteCode"]
        status, payload = request("GET", "/v1/spots", token=old["deviceToken"])
        legacy_me = member_map(payload).get(old["deviceId"], {})
        check(
            "legacy list keeps plaintext label and mode",
            status == 200
            and (payload or {}).get("cryptoMode") == "plaintext"
            and legacy_me.get("displayName") == f"Legacy {stamp}",
            repr(payload),
        )
        status, body = request(
            "PUT",
            "/v1/spot",
            {"latitude": 52.0, "longitude": 21.0, "accuracyM": 5, "savedAt": iso(saved_a)},
            token=old["deviceToken"],
        )
        check("legacy spot upload", status == 204, f"{status} {body}")
        status, payload = request("GET", "/v1/spots", token=old["deviceToken"])
        legacy_spot = member_map(payload).get(old["deviceId"], {}).get("spot") or {}
        check(
            "legacy coordinates stay plaintext",
            close(legacy_spot.get("latitude", 0), 52.0)
            and close(legacy_spot.get("longitude", 0), 21.0),
            repr(legacy_spot),
        )
        status, old_b = request(
            "POST",
            "/v1/groups/join",
            {"inviteCode": legacy_code, "displayName": f"Legacy B {stamp}"},
        )
        check(
            "legacy join still works",
            status == 200 and old_b and old_b.get("groupId") == legacy_group,
            f"{status} {old_b}",
        )
        if old_b:
            created.append((old_b["deviceToken"], "legacy-b"))
        status, body = request(
            "PATCH", "/v1/device", {"displayName": "WA 99999"}, token=old["deviceToken"]
        )
        check("legacy car label change", status == 204, f"{status} {body}")
        status, payload = request("GET", "/v1/spots", token=old["deviceToken"])
        check(
            "legacy label is visible",
            member_map(payload).get(old["deviceId"], {}).get("displayName") == "WA 99999",
            repr(payload),
        )
        status, body = request(
            "POST", "/v1/groups/rotate", token=old["deviceToken"], admin=old["adminToken"]
        )
        check("legacy rotate still works", status == 200 and body.get("inviteCode"), f"{status} {body}")
        legacy_new_code = (body or {}).get("inviteCode")
        status, _ = request(
            "POST",
            "/v1/groups/join",
            {"inviteCode": legacy_code, "displayName": "Old legacy code"},
        )
        check("old legacy code is rejected", status == 404, f"{status}")

        # --- plaintext validation ---
        status, body = request(
            "PUT", "/v1/spot", {"latitude": 91, "longitude": 0}, token=old["deviceToken"]
        )
        check(
            "out-of-range latitude is rejected",
            status == 400 and body == {"error": "invalid_coordinates"},
            f"{status} {body}",
        )
        status, body = request(
            "PUT",
            "/v1/spot",
            {"latitude": 0, "longitude": 0, "accuracyM": 10001},
            token=old["deviceToken"],
        )
        check(
            "excessive accuracy is rejected",
            status == 400 and body == {"error": "invalid_accuracy"},
            f"{status} {body}",
        )
        status, body = request("POST", "/v1/groups", {"displayName": "   "})
        check(
            "blank car label is rejected",
            status == 400 and body == {"error": "invalid_name"},
            f"{status} {body}",
        )
        status, body = request("GET", "/v1/spots")
        check("missing token is rejected", status == 401, f"{status} {body}")

        # --- cleanup entire encrypted group ---
        status, body = request("DELETE", "/v1/device", token=c["deviceToken"])
        check("second encrypted member leaves", status == 204, f"{status} {body}")
        created.remove((c["deviceToken"], "C"))
        status, body = request("DELETE", "/v1/device", token=a["deviceToken"])
        check("last encrypted member leaves", status == 204, f"{status} {body}")
        created.remove((a["deviceToken"], "A"))
        status, body = request("GET", "/v1/spots", token=a["deviceToken"])
        check("deleted encrypted token is rejected", status == 401, f"{status} {body}")
        status, _ = request(
            "POST",
            "/v1/groups/join",
            {
                "inviteCode": new_code,
                "displayName": "Late",
                "joinAuthKey": join_auth_key(new_secret),
            },
        )
        check("deleted encrypted invite is rejected", status == 404, f"{status}")

        # --- cleanup legacy group ---
        if old_b:
            request("DELETE", "/v1/device", token=old_b["deviceToken"])
            created.remove((old_b["deviceToken"], "legacy-b"))
        status, body = request("DELETE", "/v1/device", token=old["deviceToken"])
        check("legacy member leaves", status == 204, f"{status} {body}")
        created.remove((old["deviceToken"], "legacy-a"))
        status, _ = request(
            "POST",
            "/v1/groups/join",
            {"inviteCode": legacy_new_code, "displayName": "Late legacy"},
        )
        check("deleted legacy invite is rejected", status == 404, f"{status}")

        return 0 if failed == 0 else 1
    finally:
        for token, label in list(created):
            try:
                request("DELETE", "/v1/device", token=token)
                print(f"cleanup: removed device {label}")
            except Exception as exc:
                print(f"cleanup: failed to remove device {label}: {exc}")


if __name__ == "__main__":
    sys.exit(main())
