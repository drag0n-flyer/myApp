-- An escrow copy of a message with expiresAt expires with it (spec 6.2).
alter table escrow add column expires_at timestamptz;

create index escrow_expires_at_idx on escrow (expires_at) where expires_at is not null;
