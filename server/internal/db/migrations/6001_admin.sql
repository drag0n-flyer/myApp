create table admin_tokens (
    id         uuid primary key,
    token_hash bytea not null unique,
    label      text not null default '',
    created_at timestamptz not null,
    expires_at timestamptz not null,
    used_at    timestamptz
);
create table admin_sessions (
    id_hash      bytea primary key,
    token_id     uuid not null references admin_tokens (id) on delete cascade,
    csrf_hash    bytea not null,
    created_at   timestamptz not null,
    last_seen_at timestamptz not null,
    expires_at   timestamptz not null
);
create table admin_audit (
    id       bigserial primary key,
    at       timestamptz not null,
    actor    text not null check (actor in ('dashboard', 'cli')),
    token_id uuid,
    action   text not null,
    target   text not null default '',
    detail   jsonb not null default '{}'
);
create index admin_audit_at_idx on admin_audit (at desc, id desc);
