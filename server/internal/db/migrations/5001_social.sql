create table blocks (
    blocker    uuid not null references accounts (id) on delete cascade,
    blocked    uuid not null references accounts (id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (blocker, blocked)
);

create index blocks_blocked_idx on blocks (blocked);

create table reports (
    id               uuid primary key,
    reporter         uuid references accounts (id) on delete set null,
    reported_account uuid not null,
    conversation_id  uuid,
    reason           text not null check (reason in ('spam', 'abuse', 'other')),
    messages         jsonb not null default '[]',
    note             text not null default '',
    created_at       timestamptz not null default now()
);

create index reports_created_at_idx on reports (created_at);

create table attachments (
    id         text primary key,
    account_id uuid not null references accounts (id) on delete cascade,
    size       bigint not null,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null
);

create index attachments_account_id_idx on attachments (account_id, created_at);
create index attachments_expires_at_idx on attachments (expires_at);
