# Parked backend

Family mode uses a small backend. The Android app never talks to Supabase
directly; it calls a Cloudflare Worker, and the Worker uses the Supabase service
role key. Family mode is optional and disabled by default. The app works fully
without this backend.

New groups are end-to-end encrypted (E2EE). The Worker stores only ciphertext
for the car label and latest coordinates; it never receives the group key or the
invite-link secret. Groups created by 1.2.0/1.3.0 clients stay plaintext and
remain supported for compatibility.

Update both the schema and the Worker before expecting E2EE. A 1.3.0 Worker
ignores the `crypto` field and would silently create a plaintext group, so the
app shows the "Not end-to-end encrypted" warning for such groups.

- **Public instance (default):** <https://parked.bartixxx.workers.dev>
  (health check: `GET /v1/health`). Users do not need to configure anything.
- **Self-hosted instance:** users can enter their own HTTPS URL in
  **Settings → Family → Server → Change** before creating or joining a group.

The Worker source is in this repository, so the public instance can be audited
and anyone can run an equivalent instance.

## Data model

- Dedicated schema: `parked`.
- RLS is enabled on every table and **no policies are defined**. Only
  `service_role` has grants; `anon` and `authenticated` have no access, even if
  the schema is exposed through PostgREST.
- The latest spot per device is stored in `parked.spots` (`device_id` is
  unique). There is no cloud history. Notes and photos never leave the phone.
- For E2EE groups, `parked.spots.payload` holds an AES-256-GCM ciphertext of
  the car label, coordinates, accuracy and save time. `latitude`, `longitude`
  and `accuracy_m` are `NULL`. The payload is bound with AAD to the group,
  device and key version.
- The 256-bit group key is generated on the creating phone. It is stored there
  wrapped by a non-exportable Android Keystore key. The server stores a copy
  wrapped with a 128-bit secret that lives only in the invite-link fragment
  (`parked://join/<CODE>#<SECRET>`), plus an HMAC of the HKDF-derived join
  auth key. The Worker never sees the secret, group key or plaintext.
- The `display_name` column is the **car label** for legacy plaintext groups.
  E2EE groups store the placeholder `Encrypted`; the real label is inside the
  encrypted payload. The JSON field is still `displayName` so 1.2.0 clients
  remain compatible.

| Table | Purpose |
|---|---|
| `parked.groups` | Group id, HMAC of the invite code, hash of the admin token and optional E2EE key material. |
| `parked.devices` | Group members, legacy car label and hash of the device token. |
| `parked.spots` | Latest plaintext coordinates/accuracy or encrypted payload per device. |

Apply the DDL in order through the Supabase SQL editor or the Management API:

1. [`supabase/001_parked_schema.sql`](supabase/001_parked_schema.sql)
2. [`supabase/002_group_admin.sql`](supabase/002_group_admin.sql)
3. [`supabase/003_security_hardening.sql`](supabase/003_security_hardening.sql)
4. [`supabase/004_e2ee.sql`](supabase/004_e2ee.sql)

The files are written to be idempotent for the schema, tables, columns and
indexes. Review them before re-running after manual changes.

## API

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `GET` | `/v1/health` | none | Liveness check. |
| `POST` | `/v1/groups` | none | Create a group. Returns IDs, device token, admin token, invite code and E2EE key material once. |
| `POST` | `/v1/groups/join` | invite code + link secret | Join a group. Returns a new device token and, for E2EE, the wrapped group key. |
| `POST` | `/v1/groups/rotate` | device + admin token | Replace the invite code and E2EE link secret; existing device tokens stay valid. |
| `GET` | `/v1/spots` | device token | List cars and their latest spots (ciphertext for E2EE). |
| `PUT` | `/v1/spot` | device token | Insert or replace the caller's latest spot. |
| `DELETE` | `/v1/spot` | device token | Remove the caller's shared spot immediately. |
| `PATCH` | `/v1/device` | device token | Change the caller's car label (no-op on the server for E2EE). |
| `DELETE` | `/v1/device` | device token | Leave; deletes the device and its spot, and the group when empty. |

Device tokens are random 256-bit values sent as
`Authorization: Bearer <token>`. The admin token is sent in `X-Admin-Token` and
is only needed for invite-code rotation. Only SHA-256 hashes of tokens are
stored. Invite codes are 10 characters of Crockford base32 (50 bits) and are
stored as HMAC-SHA-256 with `CODE_PEPPER`.

For E2EE groups, a request to create or rotate must include a `crypto` object
with `keySalt`, `wrappedKey`, `keyIv` and `joinAuthHash`. The last field is the
base64url HKDF-derived join auth key, not the invite secret; the Worker hashes
it with `CODE_PEPPER` before storing. Joining requires `joinAuthKey` from the
link fragment. Encrypted spot uploads send `payload` (base64url ciphertext) and
`keyVersion` instead of coordinates.

The Worker validates coordinates, accuracy, car labels, ciphertext shape,
key versions and body size, and applies server-side timestamps, so a client
cannot upload a spot with an arbitrary future date.

### Errors

| Status | `error` | Meaning |
|---|---|---|
| `400` | `invalid_json`, `invalid_name`, `invalid_code`, `invalid_coordinates`, `invalid_accuracy`, `invalid_crypto`, `crypto_required` | Invalid request. |
| `401` | `unauthorized` | Missing or unknown device token. |
| `403` | `forbidden`, `invite_link_required`, `invalid_invite` | Wrong admin token, or the encrypted group needs a valid invite link. |
| `404` | `not_found`, `invalid_code` | Unknown route or invite code. |
| `409` | `group_full`, `key_version_mismatch` | The group already has 20 cars, or the client key version is stale. |
| `413` | `body_too_large` | Request body above 4 KB. |
| `429` | `rate_limited` | Per-IP or per-device limit exceeded. |
| `500` | `server_error` | Backend or Supabase failure. |

## Self-hosting step by step

You need a Supabase project, a Cloudflare account and Node.js 20 or newer.

### 1. Create the database

1. Create a Supabase project (any region close to your users).
2. Open the SQL editor and run `backend/supabase/001_parked_schema.sql`,
   then `002_group_admin.sql`, `003_security_hardening.sql` and
   `004_e2ee.sql`.
3. Copy the project URL (for example
   `https://YOUR_PROJECT.supabase.co`) and the `service_role` key from
   **Project Settings → API**. Keep the service role key private; it bypasses
   RLS.
4. Optional: add `parked` to **Project Settings → API → Exposed schemas**.
   The Worker sends `Accept-Profile: parked`, and RLS still blocks `anon` and
   `authenticated` either way.

### 2. Configure and deploy the Worker

```bash
cd backend/worker
npm install
npx wrangler login
npx wrangler secret put SUPABASE_URL
# paste https://YOUR_PROJECT.supabase.co

npx wrangler secret put SUPABASE_SERVICE_ROLE_KEY
# paste the service_role key

npx wrangler secret put CODE_PEPPER
# paste a random value, for example: openssl rand -base64 32

npx wrangler deploy
```

`wrangler deploy` prints your Worker URL. Check it with:

```bash
curl https://YOUR-WORKER.workers.dev/v1/health
# {"ok":true,"service":"parked"}
```

For local development, copy [`worker/.dev.vars.example`](worker/.dev.vars.example)
to `worker/.dev.vars`, fill in the values, and run `npx wrangler dev`.
**Never commit `.dev.vars`, `.env`, service role keys or the pepper.** They are
listed in the repository `.gitignore`; if one is ever committed or pasted into
an issue, rotate it immediately.

### 3. Point the app at your server

In Parked: **Settings → Family → Server → Change**, enter
`https://YOUR-WORKER.workers.dev`, and save. Then create or join a group. The
URL must use HTTPS. The server cannot be changed while the device is in a
group; leave the group first.

If you do not change it, the app uses the public instance and works without any
setup. Custom servers are intended for people who want to control the data
themselves.

## Tests

Worker helper and routing tests use only Node.js built-ins:

```bash
cd backend/worker
npm test
node --check src/index.js
```

The end-to-end script creates temporary groups and devices on a deployed
instance. It checks encrypted create/join/upload/list/rotate/leave, verifies
that ciphertext cannot be read without the group key, exercises the legacy
plaintext API, checks validation and security headers, then removes its data:

```bash
python3 backend/tests/e2e.py
# optional: PARKED_E2E_BASE=https://your-worker.example.workers.dev \
#   python3 backend/tests/e2e.py
```

It does not test the 20-member cap or the daily cleanup because both need a
large or aged data set; those are enforced by the SQL trigger and the cron job.

## Security and abuse controls

- **Rate limits.** `wrangler.toml` defines Cloudflare rate-limit bindings:
  global per-IP protection plus separate limits for create, join, read, write
  and rotate. Limits are approximate and apply per Cloudflare location, which
  is enough to stop casual scraping and invite-code guessing.
- **No browser CORS.** The Worker sends no `Access-Control-Allow-Origin` header
  and does not answer `OPTIONS`; the API is for the native app. This does not
  affect the Android client.
- **Group size cap.** A group can contain at most 20 cars. The Worker checks
  this before inserting, and the `devices_group_size` trigger in
  `003_security_hardening.sql` enforces the same limit under concurrent joins.
- **Retention.** A daily cron job deletes spots older than `RETAIN_DAYS`
  (default 30), devices that have not been seen for that long, and old empty
  groups. Only the latest spot is ever stored, so there is no location history
  to retain.
- **Constant-time admin check.** The admin-token hash is compared with a
  constant-time loop rather than `!==`.
- **RLS.** No policies exist for `anon` or `authenticated`; the Worker uses the
  service role only.
- **Invite links.** 50 bits of code entropy plus HMAC with `CODE_PEPPER`.
  Encrypted groups also require a 128-bit secret in the link fragment; it is
  never sent to the server. Rotate the link if it leaks. Members who already
  joined keep access.
- **E2EE key handling.** The raw group key exists only on family phones, wrapped
  by the Android Keystore. The Worker stores a copy wrapped by the invite
  secret and cannot unwrap it. There is no automatic key rotation in v1;
  rotating the invite link changes the secret but not the group key, so a
  removed member who kept the group key could still decrypt future spots.
  Recreate the group if you need to revoke access cryptographically.
- **Tokens on the phone.** Device and admin tokens live in the app-private
  DataStore. Android backup is disabled (`allowBackup="false"` and
  `fullBackupContent="false"`), so they are not copied to cloud backup.
- **Transport.** The app accepts only `https://` server URLs and disables
  cleartext traffic.

### What the server operator can see

For E2EE groups created by the current app, the Worker and the Supabase project
operator can see group/device identifiers, timestamps, key versions, ciphertext
lengths and request metadata, but **not** the car labels, coordinates or
accuracy. The invite secret and raw group key never reach the server.

Groups created by 1.2.0/1.3.0 clients are plaintext for compatibility. Their
Worker and Supabase operator can read the latest coordinates, car labels and
timestamps. Recreate such a group with the current app to get E2EE.

Group members can, by design, see each other's latest spot. Metadata and the
timing of updates are still visible to the server operator, so do not use
family mode for high-risk location tracking.

### Secrets checklist

| Name | Where it lives | Never |
|---|---|---|
| `SUPABASE_SERVICE_ROLE_KEY` | Cloudflare secret | Commit, put in the APK, paste in issues. |
| `CODE_PEPPER` | Cloudflare secret | Commit or share; losing it breaks invite-code lookup. |
| Device/admin tokens | Phone DataStore | Log, commit or share. |
| Invite link / secret | Phone DataStore and shared link | Post publicly or send to the server. |
| Wrapped group key | Phone DataStore | Log or share; it is useless without the Keystore. |
| `.dev.vars` / `.env` | Local machine only | Commit. |

The Worker logs request paths without query strings and never logs tokens,
codes or coordinates. Supabase error details are truncated to 300 characters.
