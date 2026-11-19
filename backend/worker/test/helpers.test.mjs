import assert from "node:assert/strict";
import test from "node:test";

import {
  MAX_GROUP_MEMBERS,
  MAX_PHOTO_BYTES,
  cleanDisplayName,
  cleanPhotoId,
  cleanPhotoIv,
  clientTimeOrNow,
  generateInviteCode,
  hashInviteCode,
  inRange,
  normalizeInviteCode,
  photoBucket,
  photoRetentionHours,
  randomToken,
  sha256Hex,
  timingSafeEqual,
  toHex,
} from "../src/index.js";

test("group size cap is a sane family value", () => {
  assert.equal(MAX_GROUP_MEMBERS, 20);
});

test("cleanDisplayName keeps car labels and strips control characters", () => {
  assert.equal(cleanDisplayName("  Volkswagen Golf  "), "Volkswagen Golf");
  assert.equal(cleanDisplayName("WA 12345"), "WA 12345");
  assert.equal(cleanDisplayName("Zażółć gęślą jaźń"), "Zażółć gęślą jaźń");
  assert.equal(cleanDisplayName("A\u0000B\u001FC"), "ABC");
  assert.equal(cleanDisplayName("   "), null);
  assert.equal(cleanDisplayName(42), null);
  assert.equal(cleanDisplayName("x".repeat(61)), null);
  assert.equal(cleanDisplayName("x".repeat(60)), "x".repeat(60));
});

test("normalizeInviteCode accepts Crockford variants and separators", () => {
  assert.equal(normalizeInviteCode("BSHR4H4P5M"), "BSHR4H4P5M");
  assert.equal(normalizeInviteCode("bshr4-h4p5m"), "BSHR4H4P5M");
  assert.equal(normalizeInviteCode("O0I1L"), null);
  assert.equal(normalizeInviteCode("IIIIIIIIII"), "1111111111");
  assert.equal(normalizeInviteCode("ABC"), null);
  assert.equal(normalizeInviteCode(42), null);
});

test("generated invite codes use the Crockford alphabet", () => {
  const code = generateInviteCode();
  assert.match(code, /^[0-9A-HJKMNP-TV-Z]{10}$/);
  assert.notEqual(generateInviteCode(), generateInviteCode());
});

test("randomToken is a 256-bit base64url value", () => {
  const token = randomToken();
  assert.match(token, /^[A-Za-z0-9_-]{43}$/);
  assert.notEqual(randomToken(), randomToken());
});

test("sha256Hex matches a known test vector", async () => {
  assert.equal(
    await sha256Hex("abc"),
    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
  );
});

test("toHex formats bytes with two digits", () => {
  assert.equal(toHex(new Uint8Array([0, 1, 15, 16, 255])), "00010f10ff");
});

test("hashInviteCode is deterministic per pepper and differs across peppers", async () => {
  const first = await hashInviteCode({ CODE_PEPPER: "pepper-a" }, "BSHR4H4P5M");
  const second = await hashInviteCode({ CODE_PEPPER: "pepper-a" }, "BSHR4H4P5M");
  const other = await hashInviteCode({ CODE_PEPPER: "pepper-b" }, "BSHR4H4P5M");
  assert.equal(first, second);
  assert.notEqual(first, other);
  assert.match(first, /^[0-9a-f]{64}$/);
});

test("timingSafeEqual compares equal-length strings", () => {
  assert.equal(timingSafeEqual("abc", "abc"), true);
  assert.equal(timingSafeEqual("abc", "abd"), false);
  assert.equal(timingSafeEqual("abc", "abcd"), false);
  assert.equal(timingSafeEqual(null, "abc"), false);
});

test("inRange rejects non-numbers and out-of-range values", () => {
  assert.equal(inRange(0, -1, 1), true);
  assert.equal(inRange(-1, -1, 1), true);
  assert.equal(inRange(1.1, -1, 1), false);
  assert.equal(inRange("1", -1, 1), false);
  assert.equal(inRange(Number.NaN, -1, 1), false);
  assert.equal(inRange(Number.POSITIVE_INFINITY, -1, 1), false);
});

test("clientTimeOrNow clamps future and stale timestamps", () => {
  const now = Date.now();
  const valid = new Date(now - 1000).toISOString();
  assert.equal(clientTimeOrNow(valid), valid);

  const future = new Date(now + 60 * 60 * 1000).toISOString();
  assert.ok(Math.abs(Date.now() - Date.parse(clientTimeOrNow(future))) < 5000);

  const stale = new Date(now - 8 * 24 * 60 * 60 * 1000).toISOString();
  assert.ok(Math.abs(Date.now() - Date.parse(clientTimeOrNow(stale))) < 5000);

  assert.ok(Math.abs(Date.now() - Date.parse(clientTimeOrNow("nope"))) < 5000);
});

test("photo ids must be UUIDs", () => {
  const id = "123e4567-e89b-42d3-a456-426614174000";
  assert.equal(cleanPhotoId(id), id);
  assert.equal(cleanPhotoId(id.toUpperCase()), id);
  assert.equal(cleanPhotoId("not-a-uuid"), null);
  assert.equal(cleanPhotoId("123e4567-e89b-62d3-a456-426614174000"), null);
  assert.equal(cleanPhotoId(null), null);
});

test("photo IVs are 12-byte base64url values", () => {
  assert.equal(cleanPhotoIv("AAAAAAAAAAAAAAAA"), "AAAAAAAAAAAAAAAA");
  assert.equal(cleanPhotoIv("A".repeat(15)), null);
  assert.equal(cleanPhotoIv("A".repeat(17)), null);
  assert.equal(cleanPhotoIv("AAAAAAAAAAAAAAA+"), null);
  assert.equal(cleanPhotoIv(null), null);
});

test("photo bucket and retention use safe defaults", () => {
  assert.equal(photoBucket({}), "parked-photos");
  assert.equal(photoBucket({ PHOTO_BUCKET: "family-photos" }), "family-photos");
  assert.equal(photoBucket({ PHOTO_BUCKET: "../evil" }), "parked-photos");
  assert.equal(photoRetentionHours({}), 24);
  assert.equal(photoRetentionHours({ PHOTO_RETENTION_HOURS: "48" }), 48);
  assert.equal(photoRetentionHours({ PHOTO_RETENTION_HOURS: "0" }), 1);
  assert.equal(photoRetentionHours({ PHOTO_RETENTION_HOURS: "9999" }), 168);
  assert.equal(MAX_PHOTO_BYTES, 2_000_000);
});
