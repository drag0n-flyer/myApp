create table servers (
    id         uuid primary key,
    public_key bytea not null unique,
    name       text not null,
    contact    text not null,
    created_at timestamptz not null default now(),
    disabled   boolean not null default false
);
