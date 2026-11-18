# Parked backend

Family mode uses a small backend. The Android app never talks to Supabase
directly; it calls a Cloudflare Worker, and the Worker uses the Supabase service
role key.

## Supabase

- Project: `Datalog` (`pwvumdskuphujfcqmmwu`), region `eu-central-1`.
- Dedicated schema: `parked` (the `grafik` schema in the same project is not
  touched).
- PostgREST exposed schemas: `public, graphql_public, grafik, parked`.
- RLS is enabled on all three tables and **no policies are defined**. Only
  `service_role` has grants; `anon` and `authenticated` have no access.
- The latest spot per device is stored in `parked.spots` (`device_id` is
  unique). There is no cloud history.

### Tables

| Table | Purpose |
|---|---|
| `parked.groups` | Group id and the hash of the invite code. |
| `parked.devices` | Group members, display name and the hash of the device token. |
| `parked.spots` | Latest latitude/longitude/accuracy per device. |

The DDL lives in [`supabase/001_parked_schema.sql`](supabase/001_parked_schema.sql).
Apply it through the Supabase SQL editor or the Management API. It is written to
be idempotent for the schema and tables; review it before re-running after
manual changes.

## Cloudflare Worker

Not created yet. Planned name: `parked`. Secrets:

- `SUPABASE_URL` — `https://pwvumdskuphujfcqmmwu.supabase.co`
- `SUPABASE_SERVICE_ROLE_KEY` — the `sb_secret_...` key for the Datalog project

The service role key must never be shipped in the APK or committed to the
repository.
