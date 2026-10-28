alter table reports
    add column handled_at timestamptz,
    add column handled_by uuid;
