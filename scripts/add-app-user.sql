-- Run in Supabase SQL Editor after these email accounts exist in Supabase Auth.
-- This inserts app_users rows only; passwords are managed by Supabase Auth.
DO $$
DECLARE
    v_missing_emails text;
BEGIN
    SELECT string_agg(required.email, ', ')
    INTO v_missing_emails
    FROM (
        VALUES
            ('admin@admin.com'),
            ('manager@manager.com'),
            ('cashier@cashier.com')
    ) AS required(email)
    WHERE NOT EXISTS (
        SELECT 1
        FROM auth.users AS auth_user
        WHERE lower(auth_user.email) = lower(required.email)
    );

    IF v_missing_emails IS NOT NULL THEN
        RAISE EXCEPTION 'Create these accounts in Supabase Auth first: %', v_missing_emails;
    END IF;

    INSERT INTO public.app_users (auth_user_id, display_name, role)
    SELECT auth_user.id, required.display_name, required.role
    FROM (
        VALUES
            ('admin@admin.com', 'Administrator', 'ADMIN'),
            ('manager@manager.com', 'Inventory Manager', 'INVENTORY_MANAGER'),
            ('cashier@cashier.com', 'Cashier', 'CASHIER')
    ) AS required(email, display_name, role)
    JOIN auth.users AS auth_user
        ON lower(auth_user.email) = lower(required.email)
    ON CONFLICT (auth_user_id) DO NOTHING;
END;
$$;

SELECT auth_user.email, app_user.display_name, app_user.role, app_user.active
FROM public.app_users AS app_user
JOIN auth.users AS auth_user ON auth_user.id = app_user.auth_user_id
WHERE lower(auth_user.email) IN (
    'admin@admin.com',
    'manager@manager.com',
    'cashier@cashier.com'
)
ORDER BY app_user.role;
