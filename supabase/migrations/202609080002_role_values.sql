-- Replace the retired manager role and add the global administrator role.
-- Keep this enum change in its own migration so the new value is committed
-- before later policies and functions use it.

alter type public.app_role rename value 'manager' to 'owner';
alter type public.app_role add value if not exists 'system_admin' before 'owner';
