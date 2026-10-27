create table accounts (
    id            uuid primary key,
    username      text not null unique,
    email         text not null,
    email_lower   text not null unique,
    display_name  text not null default '',
    find_by_email boolean not null default false,
    created_at    timestamptz not null default now()
);

create table devices (
    id           uuid primary key,
    account_id   uuid not null references accounts (id) on delete cascade,
    name         text not null,
    platform     text not null check (platform in ('iphone', 'mac')),
    created_at   timestamptz not null default now(),
    last_seen_at timestamptz not null default now()
);

create index devices_account_id_idx on devices (account_id, created_at, id);
