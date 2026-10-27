alter table push_tokens drop constraint push_tokens_kind_check;
alter table push_tokens add constraint push_tokens_kind_check check (kind in ('alert', 'voip', 'fcm', 'unifiedpush'));
