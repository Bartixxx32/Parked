-- Photo ownership and revocation records.
--
-- A tiny row per uploaded photo outlives the ciphertext. It lets the sender
-- retract a wrong photo even after every family phone has acknowledged the
-- download: the Worker keeps the photo id, group, sender and timestamps, marks
-- revoked_at when the sender deletes the photo, and recipients remove their
-- local copy on the next refresh. No image data or key material is stored here.
-- The hourly photo cleanup prunes rows after PHOTO_RECORD_RETENTION_DAYS (30).

create table if not exists parked.photo_records (
    photo_id uuid primary key,
    group_id uuid not null references parked.groups (id) on delete cascade,
    sender_device_id uuid not null,
    created_at timestamptz not null default now(),
    revoked_at timestamptz
);

-- The record deliberately has no foreign key to the sending device. It must
-- outlive that device so family phones can still learn about a retraction
-- after the sender leaves the group. Group deletion still cascades.
alter table parked.photo_records
    drop constraint if exists photo_records_sender_device_id_group_id_fkey;

create index if not exists photo_records_group_revoked_idx
    on parked.photo_records (group_id, revoked_at);
create index if not exists photo_records_created_idx
    on parked.photo_records (created_at);

alter table parked.photo_records enable row level security;

-- The Worker uses the service role, which bypasses RLS. No policies are added
-- on purpose: anon and authenticated roles have no access.
grant all on parked.photo_records to service_role;

-- Photos uploaded by the previous Worker version are still pending for a few
-- hours; backfill their ownership so they can be retracted too.
insert into parked.photo_records (photo_id, group_id, sender_device_id, created_at)
select id, group_id, sender_device_id, created_at
from parked.photos
on conflict (photo_id) do nothing;
