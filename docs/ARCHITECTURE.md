# Parked architecture

This document describes how the app decides to save a spot, where it keeps state and
what the reliability limits are on modern Android.

## Layers

```
UI (Compose) ── MainViewModel ── AppContainer
                                   │
      ┌────────────────────────────┼─────────────────────────────┐
      ▼                            ▼                             ▼
 SettingsRepository          ParkingRepository            DiagnosticsRepository
 TriggerStateRepository      (Room: parking_spots)        (Room: diagnostics_events)
      │                            ▲
      │                            │ ParkingSink
      ▼                            │
 BluetoothMonitorService ── ParkingCaptureEngine ── PlatformLocationSource
      │                            │
      ▼                            │
 BluetoothRepository              LocationValidator
      │
      ├─ dynamic ACL / adapter / bond broadcasts
      └─ 20 s poll + profile proxies (HFP/A2DP/LE Audio)
```

`AppContainer` is created once in `ParkedApp.onCreate` and shared by the Activity, the
service and the receivers. The Room database and DataStore are process singletons.

## Data storage

### Room — `parked.db`

`ParkingSpot`:

| Column | Notes |
|---|---|
| `id` | autoincrement PK |
| `latitude`, `longitude` | doubles, validated before saving |
| `accuracyMeters` | null only for manual entries |
| `savedAtEpochMs` | save time |
| `fixTimeEpochMs` | fix time (may be older) |
| `note`, `photoPath` | note max 500 characters, photo in private storage |
| `source` | `AUTO`, `MANUAL`, `FALLBACK` |
| `isFallback`, `isMock` | explicit markers |
| `provider`, `deviceAddress`, `deviceName` | context |
| `cycleId` | **unique index** for automatic saves |

`DiagnosticsEvent` stores at most the last 300 entries (trimmed after insert).

Photos go to `filesDir/photos`, copied as a stream with a 25 MB limit. The path is
removed together with the entry; `deletePhotoFile` verifies the canonical path so it
cannot delete a file outside the app directory.

### DataStore

- `parked_settings` — user configuration; every value is clamped to its defined range on
  read and write.
- `parked_trigger_state` — phase, `cycleId`, device address, deadline and the time of the
  last automatic save. Saved after every state change.

System backup is disabled (`allowBackup=false`, data extraction rules), so data does not
leave the device through the cloud.

## Trigger state machine

`ParkingTriggerStateMachine` is pure logic (no Android) with full JVM test coverage.

Phases:

| Phase | Meaning |
|---|---|
| `UNARMED` | no active cycle; starting while the radio is disconnected lands here |
| `CONNECTED` | the selected radio is connected; waiting for a disconnect |
| `PENDING` | disconnect detected; a save is scheduled at `pendingAtEpochMs` |
| `CAPTURING` | location acquisition and validation in progress |
| `SAVED` | cycle finished successfully or a duplicate was intentionally skipped |

### Rules

1. Only a `CONNECTED → disconnected` transition arms a save.
2. Repeated `CONNECTED` does not increase `cycleId`; repeated `DISCONNECTED` does not
   create a second deadline.
3. Reconnecting during `PENDING` cancels the timer and starts a new `CONNECTED` cycle.
4. The timer does nothing before the deadline; after it, it moves to `CAPTURING` exactly
   once.
5. `CAPTURING` is not interrupted by another `DISCONNECTED`.
6. Success → `SAVED`, failure → `UNARMED` (a new connection is required; there is no
   automatic retry loop that could save a wrong spot).
7. If less than 2 minutes have passed since the last automatic save, the save is skipped
   (`SuppressDuplicate`) — protection against radio flapping.
8. Service start:
   - `PENDING` in the future → resume the same deadline,
   - `PENDING` past its deadline within a 10-minute window → immediate `CAPTURING`,
   - older `PENDING` → reject,
   - `CONNECTED` but the radio is physically disconnected → `UNARMED` (we do not know
     when the car was parked; no false point is saved).
9. Changing the selected device cancels the timer and capture and clears state.
10. Stopping monitoring clears state and cancels scheduled work.

`cycleId` is monotonic within the current state; the unique database index is the second
line of defence in a process race.

### Fix at disconnect (prefetch)

After the `CONNECTED → PENDING` transition the service immediately starts
`ParkingCaptureEngine.acquire` with one continuous attempt and a 20 s limit
(`PREFETCH_MAX_SECONDS`). The ready, validated fix is kept in memory (`AcquiredFix`) and
written only after the delay elapses via `save` — without asking for the location again.
This makes the saved coordinates correspond to the moment of disconnection, not the
moment the timer ends. Reconnecting, changing the device, stopping monitoring and a
duplicate cancel both the timer and an in-flight acquisition. If the prefetch finishes
with an error, the result is final (no immediate retry); if for some reason there is no
prefetch, the service performs a regular `capture` with the full timeout.

## Monitoring service

`BluetoothMonitorService`:

- starts as a foreground service with type `connectedDevice|location`,
- if `startForeground` with the location type throws (missing permission, system
  restriction), it falls back to `connectedDevice`,
- foreground notification (low-priority, silent channel) with a "Stop" action — the only
  notification in the app; Parked does not send save or error notifications (the old
  `parked_events` channel is removed at startup),
- dynamic `BroadcastReceiver` for:
  - `ACTION_ACL_CONNECTED`, `ACTION_ACL_DISCONNECTED`,
  - adapter `ACTION_STATE_CHANGED`,
  - `ACTION_BOND_STATE_CHANGED`,
- broadcast filter: if the extra contains a device other than the selected one, the event
  is ignored,
- a 20 s poll as a supplement to broadcasts (some vendor overlays drop them),
- `PowerManager.PARTIAL_WAKE_LOCK` only during capture (max 3 min),
- `START_STICKY`, `stopWithTask=false`,
- after a process restart the state is restored from DataStore.

Fresh state is propagated to the UI through `MonitorStateHolder` (a process-wide
StateFlow).

### Start and background restrictions

- Android 12+ forbids starting an FGS from the background without an exemption (for
  example from an Activity, boot or PendingIntent).
- `BootReceiver` tries to restart the service after `BOOT_COMPLETED`,
  `MY_PACKAGE_REPLACED` and selected QuickBoot actions, but only when monitoring is
  enabled, a device is selected and permissions are still granted.
- `MonitorWatchdogWorker` checks every 15 minutes whether the service is alive. If not
  and permissions are missing, it writes a warning to Diagnostics instead of retrying
  forever (no notification).
- If the system forbids the start, the event goes to the logs; the user resumes
  monitoring by opening the app (starting from an Activity is allowed).
- None of these mechanisms is a guarantee — they are best effort. ColorOS may kill the
  process despite the FGS, the watchdog and disabled optimization.

## Location

`LocationSource` returns a single fix or `null`; a missing permission never throws.

`PlatformLocationSource` uses `LocationManager` with these providers:

1. GPS,
2. network,
3. (from API 31) the platform `FUSED_PROVIDER`.

All enabled providers run for the whole time budget. A fix with accuracy ≤ 10 m and a
timestamp inside the −3…+10 s window ends the wait immediately. Otherwise the best fix
seen so far is returned after the timeout. The platform fused provider combines GPS,
Wi-Fi and cell data, so in cities it is often better than raw GPS. The selected source
(`provider`) is stored with the point and shown in history and on the map card.

`PlatformLocationSource` ends the wait early when a fix is genuinely good: GPS ≤ 15 m or
any provider ≤ 10 m. This prevents saving the first, often network or cached, fix.

`ParkingCaptureEngine`:

1. Two attempts of `timeout/2` (min. 1 s); the disconnect prefetch uses one attempt with
   the timeout shortened to 20 s.
2. Every fix passes `LocationValidator`:
   - finite coordinates, within ranges, not `(0,0)`,
   - known, non-negative accuracy,
   - age ≤ 2 min (fresh fix), 60 s tolerance for a clock in the future,
   - accuracy ≤ the user limit.
3. If there is no fresh fix and fallback is enabled: the last known location
   (GPS/network/passive/fused), validated against the fallback age limit.
4. Write to Room with `source` and `isFallback`; the result is `Saved`, `Duplicate` or
   `Failed`.
5. `acquire` and `save` are separate: a fix acquired at disconnect keeps its coordinates
   and `fixTimeEpochMs`, while `savedAtEpochMs` is the actual save time.
6. A storage error is not masked — it goes to the log (without a notification).

Validation rejects typical "junk" points but is not an anti-spoofing system: mock
locations are marked, not blocked.

## Map and compass

The map screen uses osmdroid with OSM tiles. Pins are drawn by a custom `Overlay` on the
canvas (fixed size, colour depending on fallback, halo for the selected point), so they
do not depend on osmdroid's default balloons. The selected point also gets a subtle
accuracy circle: its centre is the exact coordinate (the pin tip), and the radius comes
from `accuracyMeters`, corrected for latitude. Tapping a pin selects the spot, and the
bottom card shows the date, coordinates, accuracy, fix source, note and a navigation
button. Map position and zoom are stored in `rememberSaveable`, so switching tabs does
not reset them.

The compass (`TargetCompass`) draws a dial rotated by the current device azimuth and a
needle pointing at the target bearing. The azimuth comes from `TYPE_ROTATION_VECTOR`, or
from the accelerometer + magnetometer when unavailable; screen rotation is taken into
account. Distance and bearing are calculated with `Location.distanceBetween` between the
current position and the selected (or latest) spot. The target is shown in metres,
degrees and a cardinal direction.

## Localization

The UI, notifications and user-facing errors are localized in English (default) and
Polish (`values-pl`). Android picks the language from the system settings, and the app
declares both locales in `locales_config.xml` for the Android 13+ per-app language
picker. The diagnostics log is technical and always written in English, so reports are
consistent regardless of the device language.

## Export

`ParkingExporter` is pure JVM code:

- JSON — manual construction with control-character escaping, `schemaVersion: 1`,
- CSV — header, quoting of commas/quotes/newlines,
- GPX 1.1 — valid XML, `wpt`, fix time, description, `Parking` symbol.

Tests parse GPX through `DocumentBuilderFactory`, so invalid XML fails CI.

Export writes through `ContentResolver.openOutputStream(uri)` selected in SAF; the app
has no permission to access all storage.

## Security and privacy

- No `INTERNET` use except OpenStreetMap tiles and the optional, opt-in family
  sync. Notes and photos are never uploaded; family mode shares only the latest
  spot, and new groups encrypt its car label, coordinates, accuracy and
  timestamps before upload. The default family server works without
  configuration; a self-hosted HTTPS server can be set in
  **Settings → Family → Server**.
- `android:usesCleartextTraffic="false"`.
- Backup disabled, no system data export.
- Photos and the database live in the app's private sandbox.
- Bluetooth receiver registered as `RECEIVER_NOT_EXPORTED`.
- `BootReceiver` is exported (a system broadcast requirement), but it does nothing beyond
  checking its own settings and trying to start its own service.
- Minimal permissions: location, Bluetooth, FGS, boot, wake lock. `POST_NOTIFICATIONS` is
  declared in the manifest, but the app **does not request** it — the required FGS
  notification may then appear only in the task manager, and monitoring keeps working.
  No SMS, contacts, microphone, camera (photos via SAF) or file access.

## Tests and manual checklist

Automatic (JVM, run in CI):

- trigger state: all transitions and edge cases,
- location validator,
- capture engine with fake source/sink (including saving a fix acquired at disconnect
  without changing its coordinates),
- exporters,
- compilation of all Compose screens and services.

Manual (phone, preferably OnePlus/ColorOS):

1. Pair the radio, select it in the app, enable monitoring.
2. Connect → disconnect: fix immediately, save after the delay, entry in history.
3. Connect → disconnect → connect during the delay: no save, cancellation.
4. Short connection loss (radio out of range) and return: no duplicate.
5. Disable GPS: error or a fallback marked as emergency.
6. Set the accuracy limit to 10 m indoors: an error instead of a wrong point.
7. Reboot the phone with monitoring enabled: the service restarts (or an entry appears in
   Diagnostics).
8. Kill the process from recents: the FGS should survive; if not — the watchdog or app
   start handles it.
9. Several quick disconnects: at least 2 minutes between automatic saves.
10. Locked phone (Doze): saving still happens if the system did not kill the service.
11. Export JSON/CSV/GPX and open the GPX in another map app.
12. Add a photo and a note, then delete the entry (photo files disappear).
13. Release update over the previous version: installation succeeds (same key).

## CI/CD

- `android.yml`: push/PR to `main`, `workflow_dispatch`; JDK 21, SDK 36, tests, debug
  APK, signature and metadata verification, artifact, test reports.
- `release.yml`: tag `v*` or manual dispatch; decodes the keystore from secrets, builds
  `assembleRelease`, verifies with `apksigner`, publishes the APK and SHA-256 in the
  Release. Release APKs are aligned and signed with build-tools 34.0.0 for compatibility
  with F-Droid's reproducibility verification.
- The signing key is stable and stored only in GitHub secrets; without it updates would
  not be possible.
- F-Droid rebuilds the tagged source and verifies that its APK matches the upstream
  binary; `AllowedAPKSigningKeys` pins the upstream certificate fingerprint.
