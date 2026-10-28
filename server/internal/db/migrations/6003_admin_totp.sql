-- Optional two-step sign-in (TOTP, RFC 6238) for an admin.
alter table admin_users
    add column totp_secret      bytea,
    add column totp_enabled_at  timestamptz,
    add column totp_last_step   bigint,
    add column totp_pending_at  timestamptz;
-- The 20-byte secret is stored RAW (not encrypted): the server has no key
-- meant for protecting it. Anyone who can read this table can make codes, as
-- they can already read the password hashes. It is set but not yet confirmed
-- while totp_enabled_at is null; totp_pending_at then says when it was made
-- (a pending secret is good for 10 minutes).
comment on column admin_users.totp_secret is 'raw 20-byte TOTP secret, not encrypted at rest; pending until totp_enabled_at is set';
comment on column admin_users.totp_enabled_at is 'when the admin confirmed a code and two-step sign-in turned on';
comment on column admin_users.totp_last_step is 'last used 30-second time step; a code for this step or an older one is refused (replay)';
comment on column admin_users.totp_pending_at is 'when a not yet confirmed secret was made';
