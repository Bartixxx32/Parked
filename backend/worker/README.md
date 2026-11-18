# Parked family API

Cloudflare Worker for the optional family mode. It talks to Supabase schema
`parked`; the Android app only talks to this Worker.

## Endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `GET` | `/v1/health` | none | Liveness check. |
| `POST` | `/v1/groups` | none | Create a group; returns the invite code, device token and admin token. |
| `POST` | `/v1/groups/join` | none | Join with an invite code; returns a device token. |
| `GET` | `/v1/spots` | device token | Latest spot of every member. |
| `PUT` | `/v1/spot` | device token | Upsert the caller's latest spot. |
| `PATCH` | `/v1/device` | device token | Change the caller's display name. |
| `DELETE` | `/v1/device` | device token | Leave the group. |
| `POST` | `/v1/groups/rotate` | device + admin token | Replace the invite code. |

Device tokens are sent as `Authorization: Bearer <token>`. The admin token is
sent in `X-Admin-Token` and is only needed for rotation. Neither token is stored
in plaintext: the database keeps SHA-256 hashes. Invite codes are stored as a
keyed HMAC.

## Secrets

Set these in the Worker before deploying:

```sh
npx wrangler secret put SUPABASE_URL
npx wrangler secret put SUPABASE_SERVICE_ROLE_KEY
npx wrangler secret put CODE_PEPPER
```

- `SUPABASE_URL` — `https://pwvumdskuphujfcqmmwu.supabase.co`
- `SUPABASE_SERVICE_ROLE_KEY` — the `sb_secret_...` key for the Datalog project
- `CODE_PEPPER` — a random 32-byte value, used to HMAC invite codes

The service role key and pepper must never be committed or shipped in the APK.

## Deploy

```sh
cd backend/worker
npm install
npx wrangler deploy
```

The default URL is `https://parked.bartixxx.workers.dev`. The app also allows a
custom HTTPS server URL for self-hosting.

## Security notes

- Invite codes have 50 bits of entropy (10 characters, Crockford base32).
- Request bodies are limited to 4 KB and all coordinates are range-checked.
- Supabase RLS is enabled with no policies; only the service role can read or
  write the `parked` schema.
- The Worker never returns token or code hashes, and logs never include tokens.
