-- Local development seed data
-- This file is ONLY mounted and executed in local Docker environment and NEVER deployed to AWS.

INSERT INTO users (id, username, first_name, last_name, bio) VALUES
    ('00000000-0000-0000-0000-000000000001', 'local_dev', 'Local', 'Developer', 'Default local development user')
ON CONFLICT (id) DO NOTHING;
