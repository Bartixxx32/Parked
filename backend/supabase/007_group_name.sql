-- Shared family group name.
--
-- Run this after 006_photo_records.sql.
--
-- Plaintext groups created by older app versions store the name as plaintext,
-- just like their car labels and coordinates. E2EE groups store only
-- client-generated ciphertext; the Worker never receives the group key or the
-- plaintext name. Existing groups keep working with all three columns NULL
-- until a member renames the group from a new app version.

alter table parked.groups
    add column if not exists name text,
    add column if not exists name_payload text,
    add column if not exists name_key_version integer;

alter table parked.groups
    drop constraint if exists groups_name_shape;
alter table parked.groups
    add constraint groups_name_shape
    check (
        (name is null or char_length(name) between 1 and 60)
        and (
            name_payload is null
            or (
                char_length(name_payload) between 40 and 512
                and name_payload ~ '^[A-Za-z0-9_-]+$'
            )
        )
        and not (name is not null and name_payload is not null)
        and (
            (name_payload is null and name_key_version is null)
            or (
                name_payload is not null
                and name_key_version between 1 and 1000000
            )
        )
        and (crypto_mode = 'e2ee' or name_payload is null)
        and (crypto_mode = 'plaintext' or name is null)
    );
