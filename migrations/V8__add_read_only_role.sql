DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'coolture_reader') THEN
        CREATE ROLE coolture_reader LOGIN;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rds_iam') THEN
        GRANT rds_iam TO coolture_reader;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO coolture_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO coolture_reader;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO coolture_reader;
