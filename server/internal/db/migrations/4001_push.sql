create table push_tokens (
    device_id  uuid not null references devices (id) on delete cascade,
    kind       text not null check (kind in ('alert', 'voip')),
    sealed     bytea not null,
    updated_at timestamptz not null default now(),
    primary key (device_id, kind)
);
