create table contact_links (
    token_hash bytea primary key,
    account_id uuid not null references accounts (id) on delete cascade,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    used_at    timestamptz,
    used_by    uuid references accounts (id) on delete set null
);

create index contact_links_account_id_idx on contact_links (account_id);
create index contact_links_expires_at_idx on contact_links (expires_at);
