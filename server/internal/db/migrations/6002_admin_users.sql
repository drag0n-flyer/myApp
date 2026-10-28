create table admin_users (
    id                  uuid primary key default gen_random_uuid(),
    username            text not null unique,
    password_hash       text not null,
    disabled_at         timestamptz,
    created_at          timestamptz not null default now(),
    last_login_at       timestamptz,
    password_changed_at timestamptz not null default now()
);
alter table admin_sessions
    alter column token_id drop not null,
    add column admin_user_id uuid references admin_users (id) on delete cascade,
    add constraint admin_sessions_one_owner check ((token_id is null) <> (admin_user_id is null));
create index admin_sessions_admin_user_idx on admin_sessions (admin_user_id);
-- The audit log keeps the id of the admin after the account is removed, so
-- there is no foreign key.
alter table admin_audit add column admin_user_id uuid;
