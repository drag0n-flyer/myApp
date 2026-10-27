create table key_packages (
    id         bigserial primary key,
    device_id  uuid not null references devices (id) on delete cascade,
    data       bytea not null,
    created_at timestamptz not null default now()
);

create index key_packages_device_id_idx on key_packages (device_id, id);

create table conversations (
    id          uuid primary key,
    mode        text not null check (mode in ('e2ee', 'server_readable')),
    epoch       bigint not null default 0,
    next_seq    bigint not null default 1,
    created_at  timestamptz not null default now()
);

create table conversation_accounts (
    conversation_id uuid not null references conversations (id) on delete cascade,
    account_id      uuid not null references accounts (id) on delete cascade,
    primary key (conversation_id, account_id)
);

create table conversation_devices (
    conversation_id uuid not null references conversations (id) on delete cascade,
    device_id       uuid not null references devices (id) on delete cascade,
    primary key (conversation_id, device_id)
);

create table inbox (
    cursor          bigserial primary key,
    device_id       uuid not null references devices (id) on delete cascade,
    kind            text not null,
    conversation_id uuid not null,
    seq             bigint,
    epoch           bigint not null,
    sender_account  uuid not null,
    sender_device   uuid not null,
    payload         bytea not null,
    sent_at         timestamptz not null
);

create index inbox_device_cursor_idx on inbox (device_id, cursor);

create table client_messages (
    device_id     uuid not null references devices (id) on delete cascade,
    client_msg_id text not null,
    seq           bigint not null,
    created_at    timestamptz not null default now(),
    primary key (device_id, client_msg_id)
);

create table escrow (
    conversation_id uuid not null references conversations (id) on delete cascade,
    seq             bigint not null,
    sender_account  uuid not null,
    data            bytea not null,
    created_at      timestamptz not null default now(),
    primary key (conversation_id, seq)
);

create table escrow_access_log (
    id              bigserial primary key,
    conversation_id uuid not null,
    reason          text not null,
    at              timestamptz not null default now()
);
