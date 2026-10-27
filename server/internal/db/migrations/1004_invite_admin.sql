alter table invites
    add column id         uuid        not null default gen_random_uuid() unique,
    add column created_at timestamptz not null default now(),
    add column revoked_at timestamptz;
