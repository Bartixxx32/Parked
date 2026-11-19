# TODO

Ideas and planned work that are not started yet.

## Photo straight from the camera

Status: backlog.

Right now a spot photo can only be picked from the gallery
(`ActivityResultContracts.GetContent` in `HistoryScreen.kt`).

- Add a "Take photo" option next to the gallery picker in the spot editor.
- Use `ActivityResultContracts.TakePicture` with a `FileProvider` URI. Do not
  declare the `CAMERA` permission: the system camera app takes the picture, so
  no runtime permission is needed. Declaring it would force us to request it.
- Store the captured file in the app's internal `files/photos` directory (same
  as picked photos) and reuse the existing save/replace/remove flow in
  `ParkingRepository`.
- Handle cancellation and a missing camera app gracefully with an EN/PL error.
- Update the README feature list and verify the flow on the emulator with both
  locales.

## Family mode follow-ups

Status: v1 released in 1.2.0; car labels and server selection in 1.3.0;
end-to-end encryption is in the next release. Still needs broader on-device
testing. See [backend/README.md](backend/README.md).

Implemented in v1:

- Latest spot per device only; notes, photos and history stay local.
- Map overlay with a colour and initial per member, a family spot card with
  distance/bearing and "last seen", and navigation.
- Sync after saves, on opening the map (every 60 s) and on manual refresh;
  failed uploads are retried on the next refresh.
- Off by default; membership is kept when sharing is turned off.
- Invite code: 10 characters of Crockford base32, stored as HMAC-SHA-256 with
  `CODE_PEPPER`; per-device and admin tokens stored as SHA-256 hashes.
- Create, join, refresh, rename, rotate code, leave; the last member leaving
  deletes the group.
- Worker and schema are in [`backend/`](backend/); the server URL is
  configurable, so family mode does not depend on the public instance.
- E2EE for new groups: AES-256-GCM spot payloads, invite links with a secret in
  the URL fragment, the group key wrapped by the Android Keystore, and no
  plaintext label or coordinates sent to the server. Legacy plaintext groups
  still work.

Follow-ups:

- Add per-IP join rate limiting in front of the Worker (Cloudflare Rate
  Limiting or a WAF rule). The 50-bit code space makes guessing impractical,
  but the public endpoint is currently unthrottled.
- Decide whether the group owner can kick members in addition to rotating the
  code.
- Add automatic group-key rotation when a member leaves, so a member who kept
  the old key cannot decrypt future spots.
- Test family mode on two physical devices and fix any issues found after the
  next release.
