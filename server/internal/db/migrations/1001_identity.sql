create table invites (
    code       text primary key,
    uses_left  int not null check (uses_left >= 0),
    expires_at timestamptz not null
);

-- account_id is reserved for the account created at finish, so it has no
-- foreign key: the account does not exist until then.
create table signups (
    id                uuid primary key,
    account_id        uuid not null,
    username          text not null,
    email             text not null,
    email_lower       text not null,
    invite_code       text,
    code_hash         bytea not null,
    attempts          int not null default 0,
    verified          boolean not null default false,
    expires_at        timestamptz not null,
    webauthn_session  jsonb
);

create index signups_expires_at_idx on signups (expires_at);

create table email_codes (
    id          uuid primary key,
    email_lower text not null,
    code_hash   bytea not null,
    attempts    int not null default 0,
    expires_at  timestamptz not null
);

create index email_codes_email_lower_idx on email_codes (email_lower);

create table passkeys (
    credential_id bytea primary key,
    account_id    uuid not null references accounts (id) on delete cascade,
    public_key    bytea not null,
    sign_count    bigint not null default 0,
    aaguid        bytea,
    created_at    timestamptz not null default now(),
    last_used_at  timestamptz
);

create index passkeys_account_id_idx on passkeys (account_id);

create table sessions (
    token_hash   bytea primary key,
    account_id   uuid not null references accounts (id) on delete cascade,
    device_id    uuid not null references devices (id) on delete cascade,
    created_at   timestamptz not null default now(),
    last_used_at timestamptz not null default now()
);

create index sessions_account_id_idx on sessions (account_id);
create index sessions_device_id_idx on sessions (device_id);

create table webauthn_challenges (
    id         uuid primary key,
    session    jsonb not null,
    expires_at timestamptz not null
);

create table link_requests (
    id          uuid primary key,
    secret_hash bytea not null,
    claim_hash  bytea not null,
    device_name text not null,
    platform    text not null check (platform in ('iphone', 'mac')),
    account_id  uuid references accounts (id) on delete cascade,
    sealed      bytea,
    expires_at  timestamptz not null
);
