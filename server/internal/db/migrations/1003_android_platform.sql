alter table devices drop constraint devices_platform_check;
alter table devices add constraint devices_platform_check check (platform in ('iphone', 'mac', 'android'));
alter table link_requests drop constraint link_requests_platform_check;
alter table link_requests add constraint link_requests_platform_check check (platform in ('iphone', 'mac', 'android'));
