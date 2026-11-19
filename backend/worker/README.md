# Parked family API Worker

Cloudflare Worker for optional family mode. It talks to Supabase schema
`parked`; the Android app only talks to this Worker. See
[`../README.md`](../README.md) for the full self-hosting guide, security model
and API documentation.

## Local checks

```bash
cd backend/worker
npm install
npm test
node --check src/index.js
```

The Node tests cover input validation, token/code helpers, constant-time
comparison, security headers, CORS removal, body limits and rate-limit
responses. They do not need Cloudflare credentials or a Supabase project.

## Configuration

`wrangler.toml` defines:

- `RETAIN_DAYS` — daily cleanup cutoff for stale spots/devices/groups.
- A daily cron trigger at 03:17 UTC.
- Rate-limit bindings for global, create, join, read, write and rotate.
  The `namespace_id` values must be unique within your Cloudflare account.

Worker secrets:

```bash
npx wrangler secret put SUPABASE_URL
npx wrangler secret put SUPABASE_SERVICE_ROLE_KEY
npx wrangler secret put CODE_PEPPER
```

`SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY` and `CODE_PEPPER` are secrets. Never
commit them, put them in the APK or paste them into issues. For local
development use a `.dev.vars` file based on
[`.dev.vars.example`](.dev.vars.example); it is git-ignored.

## Deploy

```bash
cd backend/worker
npm install
npx wrangler login
npx wrangler deploy
```

Before the first deploy, apply all three SQL files in `backend/supabase/` in
order. Then point the app at the Worker URL in
**Settings → Family → Server → Change**. If you leave the default, the app uses
the public instance and needs no configuration.
