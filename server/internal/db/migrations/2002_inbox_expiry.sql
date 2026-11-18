-- Optional expiry of an undelivered message (people-and-groups spec 6.2).
alter table inbox add column expires_at timestamptz;

create index inbox_expires_at_idx on inbox (expires_at) where expires_at is not null;
