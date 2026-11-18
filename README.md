# Parked

**Parked** automatically saves your parking spot when the phone disconnects from a
selected car radio (Bluetooth). The app is local-first, open source and requires no
account.

- Kotlin + Jetpack Compose + Material 3
- Room (history and logs), DataStore (settings and trigger state)
- OpenStreetMap via osmdroid — no API keys and no paid services
- Android 10 (API 29) or newer, built against API 36

The app is available in **English and Polish**; the language follows the phone settings.
It is prepared for [F-Droid](https://f-droid.org/) and uses only free and open source
dependencies — no Google Play Services.

> Download the ready APK from the **Releases** section. Debug artifacts are available in
> every successful GitHub Actions run. F-Droid publishing is in progress.

---

## Features

- Select one paired Bluetooth device; other devices are ignored.
- Automatic save after disconnecting, with a configurable delay (15 s by default).
  The location is acquired **immediately at disconnect**, while the save waits for the
  delay to expire — so the point reflects where the car was parked, not where you walked
  during those 15 seconds.
- Reconnecting during the countdown cancels the save.
- Duplicate protection: one save per connection cycle, a unique `cycleId` in the
  database and a minimum interval between automatic saves.
- Location: the platform fused provider on Android 12+ plus system GPS and network
  providers, coordinate/accuracy/age validation, best-fix selection, and an optional
  last-known fallback.
- History: date, coordinates, accuracy, fix source (GPS/Fused/Network), note and an
  optional photo.
- Manual save of the current location and manual entry of coordinates.
- OpenStreetMap: pins for all spots, tap to select, accuracy circle around the selected
  point, current position and an external navigation button.
- Built-in target compass: a dial that reacts to phone rotation, cardinal direction
  (for example `NE`), bearing in degrees and distance in metres to the selected or latest
  spot.
- Optional family mode, off by default: create or join a group with an invite code and
  see each member's latest parked car on the map. Only the latest coordinates, accuracy,
  display name and timestamps are uploaded; notes and photos stay local. The backend is
  self-hostable and the app can point at any HTTPS instance
  (see [backend/README.md](backend/README.md)).
- History export to **JSON**, **CSV** and **GPX** via the system file picker.
- Diagnostics screen: service state, permissions, GPS test and local logs (max 300
  entries).
- No save or error notifications. The only notification is the Android-required silent,
  low-priority notification of the monitoring foreground service (it can be hidden in
  the system settings — monitoring keeps working).
- Monitoring is restored after a phone reboot and after an app update (best effort).
- No ads, analytics, telemetry, account or location uploads.

## Installation

1. Open [Releases](https://github.com/Bartixxx32/Parked/releases/latest) and download
   `Parked-<version>.apk`.
2. Open the file on the phone. Android may ask for permission to install from this
   source.
3. Install and launch the app.

The debug build uses the `com.bartixxx.parked.debug` application ID, so it can coexist
with the release build. Release updates install over the previous version only when they
are signed with the same key — official releases are.

> Upgrading from the old `io.github.bartixxx32.parked` package is not possible. That
> package used a different application ID, so the new release must be installed
> separately; uninstalling the old version removes its local data.

## First launch

1. **Pair the phone with the car radio** in the system Bluetooth settings.
2. In the app: **Settings → Car radio → refresh** and select your radio.
3. Grant permissions:
   - Bluetooth (`BLUETOOTH_CONNECT` on Android 12+),
   - precise or approximate location,
   - **background location → "Allow all the time"**.
   The app does not ask for notification permission.
4. Disable battery optimization and enable autostart (see below).
5. Turn on **Monitoring** on the Dashboard.

From then on, disconnecting from the radio immediately starts a location fix
(max. 20 s), and the point is saved after the configured delay. If the radio reconnects
in the meantime, the save is cancelled and the acquired point is discarded.

## Important settings

| Setting | Default | Meaning |
|---|---:|---|
| Delay after disconnecting | 15 s | Time to reconnect; reconnecting cancels the save. The fix itself starts immediately. |
| GPS timeout | 45 s | After this time the last attempt / fallback is used. Also applies to the fused provider. |
| Max accuracy | 100 m | Less accurate fixes are rejected. |
| Last known fallback | enabled | Emergency point, clearly marked in history. |
| Max fallback age | 12 h | Older points are not used. |

Parked has no save-notification setting — it simply does not send such notifications.

Ranges are validated in DataStore; out-of-range values are clamped.

## OnePlus / ColorOS / Android 16 — reliability

Android increasingly restricts background work. Parked uses a foreground service of type
`connectedDevice|location`, dynamic Bluetooth broadcasts, periodic state checks, a
WorkManager watchdog and boot restart — but **no app can guarantee** that the system will
not kill it.

On OnePlus with ColorOS additionally:

1. **Settings → Apps → Parked → Battery → Unrestricted** (or "Don't optimize").
2. **Autostart**: Settings → Apps → Autostart → enable Parked. The "Autostart" button in
   the app opens the right screen when the vendor provides one.
3. In the recents panel, **lock Parked** (padlock) so it is not removed by "clear all".
4. Allow **background activity** in the app details if ColorOS offers that option.
5. Set location to **"Allow all the time"**.
6. Do not turn off Bluetooth or enable airplane mode for extended periods.

If the system still stops the service, Parked tries to restart it the next time the app
is opened, and the WorkManager watchdog retries about every 15 minutes. Parked sends no
notifications about such events — details are in Diagnostics.

**There is no guarantee of a save.** When in doubt, use the "Save current location"
button on the Dashboard.

## How it works

```
radio connects          disconnect: fix           after delay: save
        │                         │                            │
        ▼                         ▼                            ▼
   CONNECTED ───────────────► PENDING ───────────────────► CAPTURING
        ▲                         │                            │
        │   reconnect             │ cancels save and fix       │ saves the ready fix
        └─────────────────────────┘                            ▼
                                                          SAVED / error
```

- The location fix starts **immediately after disconnecting** (max. 20 s) and is kept in
  memory. The delay only postpones the save, so walking away from the car does not move
  the coordinates.
- On Android 12+ the platform fused provider is queried together with system GPS and
  network/Wi-Fi providers. In dense areas the point can therefore come from Wi-Fi/cell
  data instead of raw GPS.
- After a Bluetooth broadcast the state is checked immediately and again after 1, 3, 6
  and 10 s, so a temporarily stale profile proxy does not wait for the 20-second poll.
- Updates never move already saved points — the new algorithm only affects future saves.
  Every point also stores its fix source.
- Starting the service while the radio is already disconnected **does not save** a point
  — there is no way to know when the car was parked.
- Repeated connect/disconnect broadcasts are idempotent.
- The state (`TriggerState`) is persisted in DataStore after every change; after a process
  restart a pending save is resumed only within a 10-minute window.
- Each cycle has a unique `cycleId` with a unique index in Room — even a concurrent save
  cannot create a duplicate.
- Two automatic saves are at least 2 minutes apart (protection against radio flapping).

Details: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Privacy

- All data (history, notes, photos, logs, settings) is stored exclusively in the app's
  private storage.
- No ads, analytics, Crashlytics, telemetry or user accounts.
- Without family mode the app does not send coordinates to any server.
- Family mode is opt-in. When enabled, the app sends the latest coordinates, accuracy,
  display name and timestamps to the configured family server so other group members can
  see the car. Notes, photos and spot history are never uploaded. Leaving the group
  deletes the member's cloud data.
- Otherwise the only network traffic is downloading map tiles from OpenStreetMap servers
  while the map screen is open (the server sees the IP address and tile area, like any
  website).
- Export creates a file only where you point the system file picker.
- Android backup is disabled; data does not reach the cloud through system backup.

## Export

In History, select the download icon and a format:

- **JSON** — full data, versioned schema (`schemaVersion: 1`).
- **CSV** — convenient for spreadsheets; notes are quoted correctly.
- **GPX** — waypoints for navigation, field photos and other map apps.

Files are not stored in the repository or sent automatically.

## Building and tests

The project builds in GitHub Actions (Ubuntu x86_64), not on a Raspberry Pi.

- CI: `.github/workflows/android.yml`
  - `testDebugUnitTest`, `assembleDebug`, APK verification with `aapt2` and
    `apksigner`, artifact `Parked-debug-apk` plus test reports.
- Release: `.github/workflows/release.yml`
  - triggered by a `v*` tag or manually, signs the APK with the key from GitHub secrets
    and publishes the file to GitHub Releases. Release APKs are signed with Android
    build-tools 34.0.0 so F-Droid can verify reproducibility.

Local requirements (optional): JDK 17+, Android SDK 36, Gradle wrapper 8.14.3.

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

### Signing secrets

Repository settings (Actions → Secrets) contain:

| Secret | Meaning |
|---|---|
| `PARKED_KEYSTORE_BASE64` | PKCS12 keystore encoded as base64 (no newlines) |
| `PARKED_KEYSTORE_PASSWORD` | keystore password |
| `PARKED_KEY_ALIAS` | key alias |
| `PARKED_KEY_PASSWORD` | key password (may be the same) |

Without these secrets the release fails instead of publishing an unsigned file.

## Tests

JVM unit tests cover, among others:

- connect → disconnect → save,
- a short connection loss followed by reconnect (cancellation),
- repeated and duplicated Bluetooth events,
- service restart with a pending save (resume and reject stale saves),
- start while the radio is already disconnected (no false save),
- device change and delay change,
- missing GPS, low accuracy, invalid coordinates and stale fallback,
- duplicate cycle and minimum interval between saves,
- a fix acquired at disconnect saved later without moving the coordinates,
- JSON/CSV/GPX export (XML validity and quoting),
- invite-code normalization and validation (Crockford base32).

Scenarios that require a physical phone (reboot, process killed by ColorOS, a real radio
connection) are described in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) as a manual
checklist — they cannot be reliably simulated in JVM tests.

## Troubleshooting

| Symptom | What to check |
|---|---|
| No save after disconnecting | Is monitoring enabled? Is the selected radio the same one the phone connects to? Is the service running (Diagnostics)? |
| "Service not running" | Disable battery optimization, enable autostart, open the app and restart monitoring. |
| Very inaccurate save | Lower the accuracy limit, check GPS outdoors; a fallback point is marked in history. |
| No background location | The permission must be set to "Allow all the time"; on Android 11+ this is a separate settings screen. |
| No map | Check the internet connection; OSM tiles require network. History pins work offline. |
| Compass does not rotate | The phone has no orientation sensor — the arrow still points relative to north. |
| I don't want the monitoring notification | It is the Android-required foreground service notification. Hide the channel in Settings → Notifications; monitoring keeps working. |
| Photo cannot be added | Pick an image from device storage; the limit is 25 MB. |

## Project structure

```
app/src/main/java/com/bartixxx/parked/
├── bluetooth/       # bonded devices, connection detection
├── capture/         # location acquisition and validation engine
├── data/            # Room, DataStore, repositories, trigger state
├── export/          # JSON / CSV / GPX
├── family/          # optional family groups, API client, credentials
├── location/        # LocationManager platform providers
├── monitor/         # FGS, boot receiver, watchdog
├── notifications/   # the only channel: required FGS notification
├── ui/              # Compose: dashboard, history, map with compass, settings, diagnostics
└── util/            # permissions, system intents
```

## F-Droid

F-Droid metadata lives in `fastlane/metadata/android/` (English and Polish) and the
recipe is prepared in [docs/FDROID.md](docs/FDROID.md). The app uses reproducible builds:
F-Droid rebuilds the tagged source and publishes the APK signed with the upstream key.

## License and map data

Code: **GNU GPL v3.0** — see [LICENSE](LICENSE).

Map: data © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright),
ODbL license. Tiles: OpenStreetMap Standard. The app is not affiliated with the
OpenStreetMap Foundation.

Application ID: `com.bartixxx.parked`.
