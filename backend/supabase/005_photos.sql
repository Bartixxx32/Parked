-- End-to-end encrypted family photos.
--
-- Run this after 004_e2ee.sql.
--
-- Ciphertext lives in a private Supabase Storage bucket. The Worker stores only
-- per-photo metadata and the list of family devices that still need to confirm
-- the download. When every recipient acknowledges the photo, the Worker deletes
-- the object from Storage and the metadata row. A TTL is the safety net for
-- phones that stay offline.

create table if not exists parked.photos (
    id uuid primary key,
    group_id uuid not null references parked.groups (id) on delete cascade,
    sender_device_id uuid not null,
    object_path text not null unique,
    iv text not null,
    key_version integer not null,
    size_bytes integer not null,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    foreign key (sender_device_id, group_id)
        references parked.devices (id, group_id) on delete cascade,
    check (char_length(iv) between 12 and 32),
    check (key_version between 1 and 1000000),
    check (size_bytes between 1 and 4194304),
    check (expires_at > created_at)
);

create index if not exists photos_group_expires_idx
    on parked.photos (group_id, expires_at);
create index if not exists photos_expires_idx
    on parked.photos (expires_at);
create index if not exists photos_sender_created_idx
    on parked.photos (sender_device_id, created_at desc);

create table if not exists parked.photo_deliveries (
    photo_id uuid not null references parked.photos (id) on delete cascade,
    device_id uuid not null references parked.devices (id) on delete cascade,
    acked_at timestamptz,
    primary key (photo_id, device_id)
);

create index if not exists photo_deliveries_device_pending_idx
    on parked.photo_deliveries (device_id)
    where acked_at is null;

alter table parked.photos enable row level security;
alter table parked.photo_deliveries enable row level security;

-- The Worker uses the service role, which bypasses RLS. No policies are added
-- on purpose: anon and authenticated roles have no access.
grant all on parked.photos to service_role;
grant all on parked.photo_deliveries to service_role;

-- Private bucket for ciphertext only. The Worker accesses it with the service
-- role; no storage policy is granted to anon or authenticated clients. The
-- 2 MB limit matches MAX_PHOTO_BYTES in the Worker. If the bucket already
-- exists, keep it private and enforce the same limit.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
    'parked-photos',
    'parked-photos',
    false,
    2000000,
    array['application/octet-stream']
)
on conflict (id) do update
    set public = false,
        file_size_limit = 2000000,
        allowed_mime_types = array['application/octet-stream'];
