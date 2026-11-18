-- When an account joined a conversation; orders ownership succession.
alter table conversation_accounts
    add column joined_at timestamptz not null default now();
