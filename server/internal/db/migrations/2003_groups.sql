alter table conversations
    add column kind text not null default 'direct' check (kind in ('direct', 'group')),
    add column members_version bigint not null default 0;

alter table conversation_accounts
    add column role text not null default 'member' check (role in ('owner', 'admin', 'member'));

-- At most one owner per conversation, enforced by the database as well.
create unique index conversation_accounts_one_owner_idx
    on conversation_accounts (conversation_id) where role = 'owner';
