-- The invite code can be rotated by the group creator. Rotation needs an admin
-- token that is separate from the per-device tokens.
alter table parked.groups
    add column if not exists admin_token_hash text;
