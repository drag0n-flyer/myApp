-- The authenticator flags byte (UP, UV, BE, BS) from registration. WebAuthn
-- requires that the backup-eligible flag never changes, so sign-in checks it.
alter table passkeys add column flags smallint not null default 0;
