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

## Family mode (shared parking spots)

Status: backend schema created in the Datalog Supabase project; Worker and app
integration not started.

Goal: an opt-in group where members see each other's most recently parked car
on the map. A group is identified by a high-entropy invite code; anyone who
knows the code can join.

Scope (v1):

- Only the latest spot per device is synced. No spot history over the network.
- Notes and photos stay local and are not uploaded.
- The map shows the latest car of every member with a display name, colour
  and "last seen" time.
- Sync on save, on opening the map and on manual refresh; queue writes while
  offline and upload them later.
- Off by default. The app remains fully usable without family mode.

Backend:

- Android -> Cloudflare Worker -> Supabase. The service role key lives only in
  Worker secrets; the app never talks to Supabase directly.
- The Worker handles auth, validation, join rate limiting and server-side
  timestamps.
- Invite code: 10-12 characters of Crockford base32, stored only as an
  HMAC/SHA-256 hash. Joining exchanges the code for a per-device token; the
  server stores only the token hash.
- Tables: `groups`, `devices`, `spots` (unique `device_id`, latest spot only).
- Endpoints: create group, join, upsert own spot, list group spots, leave,
  rotate invite code.
- Leaving a group deletes the member's spot and device record.

Security and privacy:

- High-entropy code, join rate limiting, per-device revocation and code
  rotation.
- Never ship Supabase keys in the APK.
- Document the data flow and keep family mode disabled by default.
- F-Droid: keep the Worker source and database schema in the repository and
  document self-hosting. The service must stay optional so it does not become
  a `NonFreeNet` dependency.

Open decisions:

- End-to-end encryption of uploaded payloads (postponed).
- Whether the group owner can kick members, or only rotate the code.
- Display names: generated per device or entered by the user.
