# Parked backend

Family mode uses a small backend. The Android app never talks to Supabase
directly; it calls a Cloudflare Worker, and the Worker uses the Supabase service
role key. Family mode is optional and disabled by default. The app works fully
without this backend; users who do not want to use the public instance can run
their own and point the app at it in **Settings → Family → Advanced**.

Public instance: <https://parked.bartixxx.workers.dev> (health check:
`GET /v1/health`).

## Supabase

- Project: `Datalog` (`pwvumdskuphujfcqmmwu`), region `eu-central-1`.
- Dedicated schema: `parked` (the `grafik` schema in the same project is not
  touched).
- PostgREST exposed schemas: `public, graphql_public, grafik, parked`.
- RLS is enabled on all three tables and **no policies are defined**. Only
  `service_role` has grants; `anon` and `authenticated` have no access.
- The latest spot per device is stored in `parked.spots` (`device_id` is
  unique). There is no cloud history. Notes and photos never leave the phone.

### Tables

| Table | Purpose |
|---|---|
| `parked.groups` | Group id, the HMAC of the invite code and the hash of the admin token. |
| `parked.devices` | Group members, display name and the hash of the device token. |
| `parked.spots` | Latest latitude/longitude/accuracy per device. |

The DDL lives in [`supabase/001_parked_schema.sql`](supabase/001_parked_schema.sql)
and [`supabase/002_group_admin.sql`](supabase/002_group_admin.sql). Apply them
through the Supabase SQL editor or the Management API. They are written to be
idempotent for the schema, tables and columns; review them before re-running
after manual changes.

## Cloudflare Worker

Source: [`worker/src/index.js`](worker/src/index.js), configuration:
[`worker/wrangler.toml`](worker/wrangler.toml). Worker name: `parked`.

### Secrets

| Secret | Meaning |
|---|---|
| `SUPABASE_URL` | `https://pwvumdskuphujfcqmmwu.supabase.co` |
| `SUPABASE_SERVICE_ROLE_KEY` | the `sb_secret_...` key for the Datalog project |
| `CODE_PEPPER` | secret key for HMAC-SHA-256 of invite codes |

The service role key and `CODE_PEPPER` must never be shipped in the APK or
committed to the repository. Losing `CODE_PEPPER` makes existing invite codes
impossible to look up; device tokens keep working until they are revoked.

### Endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `GET` | `/v1/health` | none | Liveness check. |
| `POST` | `/v1/groups` | none | Create a group. Returns IDs, device token, admin token and invite code once. |
| `POST` | `/v1/groups/join` | invite code | Join a group. Returns a new device token. |
| `POST` | `/v1/groups/rotate` | device + admin token | Replace the invite code; existing device tokens stay valid. |
| `GET` | `/v1/spots` | device token | List members and their latest spots. |
| `PUT` | `/v1/spot` | device token | Insert or replace the caller's latest spot. |
| `PATCH` | `/v1/device` | device token | Change the caller's display name. |
| `DELETE` | `/v1/device` | device token | Leave; deletes the device and its spot, and the group when empty. |

Tokens are random 256-bit values. Only their SHA-256 hashes are stored. Invite
codes are 10 characters of Crockford base32 (50 bits) and are stored as
HMAC-SHA-256 with `CODE_PEPPER`. The Worker validates coordinates, accuracy,
display names and body size, and applies server-side timestamps, so a client
cannot upload a spot with an arbitrary future date.

## Deploying your own instance

```bash
cd backend/worker
npx wrangler login
npx wrangler deploy
npx wrangler secret put SUPABASE_URL
npx wrangler secret put SUPABASE_SERVICE_ROLE_KEY
npx wrangler secret put CODE_PEPPER
```

Then give the HTTPS URL to the app users: **Settings → Family → Advanced →
Server URL**. The URL must use `https://`. The server cannot be changed while
the device is in a group.

## Security and privacy notes

- The group is identified only by the invite code; anyone who knows it can join.
  Share it only with people you trust. Rotate it if it leaks.
- Group members can read each other's latest coordinates, display name and
  "last seen" time through the Worker API. Nothing else is uploaded.
- Leaving the group deletes the member row and the spot from the cloud. The
  group itself is deleted when the last member leaves.
- The Worker has no per-IP join rate limiting yet. The 50-bit code space makes
  blind guessing impractical, but the public instance can still be abused for
  resource consumption. For a hardened deployment put Cloudflare Rate Limiting
  or a WAF rule in front of `/v1/groups/join`.
- The app's API client sets a `Parked/<version> (Android)` User-Agent because
  Cloudflare rejects some default library user agents with Error 1010.
