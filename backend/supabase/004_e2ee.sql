-- End-to-end encryption for family mode.
--
-- Run this after 003_security_hardening.sql.
--
-- The Worker stores only ciphertext for encrypted groups. It never receives
-- the group key or the invite secret: the phone wraps a random 256-bit group
-- key with a 128-bit secret carried in the invite link fragment.

alter table parked.groups
    add column if not exists crypto_mode text not null default 'plaintext',
    add column if not exists key_salt text,
    add column if not exists wrapped_key text,
    add column if not exists key_iv text,
    add column if not exists join_auth_hash text,
    add column if not exists key_version integer not null default 1;

alter table parked.groups
    drop constraint if exists groups_crypto_mode_check;
alter table parked.groups
    add constraint groups_crypto_mode_check
    check (crypto_mode in ('plaintext', 'e2ee'));

alter table parked.groups
    drop constraint if exists groups_key_version_check;
alter table parked.groups
    add constraint groups_key_version_check
    check (key_version between 1 and 1000000);

alter table parked.groups
    drop constraint if exists groups_crypto_shape;
alter table parked.groups
    add constraint groups_crypto_shape
    check (
        (
            crypto_mode = 'plaintext'
            and key_salt is null
            and wrapped_key is null
            and key_iv is null
            and join_auth_hash is null
        )
        or (
            crypto_mode = 'e2ee'
            and key_salt is not null
            and wrapped_key is not null
            and key_iv is not null
            and join_auth_hash is not null
        )
    );

alter table parked.spots
    add column if not exists payload text,
    add column if not exists key_version integer;

alter table parked.spots
    alter column latitude drop not null,
    alter column longitude drop not null;

alter table parked.spots
    drop constraint if exists spots_crypto_shape;
alter table parked.spots
    add constraint spots_crypto_shape
    check (
        (
            payload is null
            and key_version is null
            and latitude is not null
            and longitude is not null
        )
        or (
            payload is not null
            and key_version is not null
            and latitude is null
            and longitude is null
            and accuracy_m is null
        )
    );

alter table parked.spots
    drop constraint if exists spots_key_version_check;
alter table parked.spots
    add constraint spots_key_version_check
    check (key_version is null or key_version between 1 and 1000000);

-- Keep the existing retention index useful for encrypted rows too.
create index if not exists spots_updated_at_idx
    on parked.spots (updated_at);
