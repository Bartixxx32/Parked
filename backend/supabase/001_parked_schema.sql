-- Parked family mode: shared parking spots.
--
-- Lives in its own `parked` schema inside the shared Datalog Supabase project.
-- The Android app never talks to Supabase directly: only the Cloudflare Worker
-- uses the service role key, and RLS with no policies keeps anon/authenticated
-- roles out even if the schema is ever exposed through PostgREST.

create schema if not exists parked;

revoke all on schema parked from public;
revoke all on schema parked from anon, authenticated;

create table if not exists parked.groups (
    id uuid primary key default gen_random_uuid(),
    code_hash text not null unique,
    created_at timestamptz not null default now(),
    rotated_at timestamptz
);

create table if not exists parked.devices (
    id uuid primary key default gen_random_uuid(),
    group_id uuid not null references parked.groups (id) on delete cascade,
    display_name text not null
        check (char_length(display_name) between 1 and 60),
    token_hash text not null unique,
    created_at timestamptz not null default now(),
    last_seen_at timestamptz,
    unique (id, group_id)
);

-- One row per device: the latest parked car only, no history in the cloud.
create table if not exists parked.spots (
    id uuid primary key default gen_random_uuid(),
    group_id uuid not null,
    device_id uuid not null unique,
    latitude double precision not null
        check (latitude between -90 and 90),
    longitude double precision not null
        check (longitude between -180 and 180),
    accuracy_m double precision
        check (accuracy_m is null or accuracy_m >= 0),
    saved_at timestamptz not null,
    updated_at timestamptz not null default now(),
    foreign key (device_id, group_id)
        references parked.devices (id, group_id) on delete cascade
);

create index if not exists devices_group_id_idx on parked.devices (group_id);
create index if not exists spots_group_id_idx on parked.spots (group_id);

alter table parked.groups enable row level security;
alter table parked.devices enable row level security;
alter table parked.spots enable row level security;

-- The Worker uses the service role, which bypasses RLS. No policies are added
-- on purpose: anon and authenticated roles have no access.
grant usage on schema parked to service_role;
grant all on all tables in schema parked to service_role;
alter default privileges in schema parked grant all on tables to service_role;
