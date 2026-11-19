-- Security hardening for family mode.
--
-- Run this after 001_parked_schema.sql and 002_group_admin.sql.
--
-- The group-size cap is enforced in the database as well as in the Worker so
-- two concurrent joins cannot exceed the limit. Keep the number below in sync
-- with MAX_GROUP_MEMBERS in backend/worker/src/index.js.

create or replace function parked.enforce_group_size()
returns trigger
language plpgsql
set search_path = parked, pg_temp
as $$
declare
    member_count integer;
begin
    -- Serialise joins for one group so the count cannot race.
    perform 1
    from parked.groups
    where id = new.group_id
    for update;

    select count(*)
    into member_count
    from parked.devices
    where group_id = new.group_id;

    if member_count >= 20 then
        raise exception 'group_full' using errcode = 'P0001';
    end if;

    return new;
end;
$$;

revoke all on function parked.enforce_group_size() from public, anon, authenticated;
grant execute on function parked.enforce_group_size() to service_role;

drop trigger if exists devices_group_size on parked.devices;
create trigger devices_group_size
    before insert on parked.devices
    for each row
    execute function parked.enforce_group_size();

-- Supports the daily retention cleanup in the Worker.
create index if not exists devices_last_seen_at_idx
    on parked.devices (last_seen_at);
create index if not exists spots_updated_at_idx
    on parked.spots (updated_at);
create index if not exists groups_created_at_idx
    on parked.groups (created_at);
