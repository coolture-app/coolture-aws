-- Local development seed data
-- This file is ONLY applied by the local `migrate` compose service and NEVER deployed to AWS.
-- It runs on every `make up`, so each statement leaves existing rows alone.

INSERT INTO users (id, username, first_name, last_name, bio) VALUES
    ('00000000-0000-0000-0000-000000000001', 'local_dev', 'Local', 'Developer', 'Default local development user')
ON CONFLICT (id) DO NOTHING;

-- The account `make seed-events` and `make scrape` post as. The frontend's dev menu can act as it too.
INSERT INTO users (id, username, first_name, last_name, bio) VALUES
    ('00000000-0000-0000-0000-00000000b07a', 'coolture_bot', 'Coolture', 'Bot', 'Imports events from trojmiasto.pl')
ON CONFLICT (id) DO NOTHING;

-- Three regular users with different tastes, so their feeds differ. Their reactions come from `make seed-reactions`,
-- because the events they react to are loaded later, by `make seed-events`.
INSERT INTO users (id, username, first_name, last_name, bio) VALUES
    ('00000000-0000-0000-0000-000000000002', 'anna_demo', 'Anna', 'Demo', 'Koncerty, indie i elektronika, a w weekendy kluby'),
    ('00000000-0000-0000-0000-000000000003', 'jan_demo', 'Jan', 'Demo', 'Teatr, stand-up i kino autorskie'),
    ('00000000-0000-0000-0000-000000000004', 'ola_demo', 'Ola', 'Demo', 'Galerie, fotografia, bieganie i dobre jedzenie')
ON CONFLICT (id) DO NOTHING;

-- Only for a user with no interests yet, so a choice made in the app survives the next `make up`.
INSERT INTO user_interests (user_id, interest_key)
SELECT picks.user_id::uuid, picks.interest_key
FROM (VALUES
    ('00000000-0000-0000-0000-000000000002', 'concerts'),
    ('00000000-0000-0000-0000-000000000002', 'indie'),
    ('00000000-0000-0000-0000-000000000002', 'electronic'),
    ('00000000-0000-0000-0000-000000000002', 'clubbing'),
    ('00000000-0000-0000-0000-000000000002', 'parties'),
    ('00000000-0000-0000-0000-000000000003', 'theatre'),
    ('00000000-0000-0000-0000-000000000003', 'stand_up'),
    ('00000000-0000-0000-0000-000000000003', 'cinema'),
    ('00000000-0000-0000-0000-000000000003', 'arthouse'),
    ('00000000-0000-0000-0000-000000000003', 'author_meetings'),
    ('00000000-0000-0000-0000-000000000004', 'galleries'),
    ('00000000-0000-0000-0000-000000000004', 'museums'),
    ('00000000-0000-0000-0000-000000000004', 'photography'),
    ('00000000-0000-0000-0000-000000000004', 'running'),
    ('00000000-0000-0000-0000-000000000004', 'walks'),
    ('00000000-0000-0000-0000-000000000004', 'foodie'),
    ('00000000-0000-0000-0000-000000000004', 'family_events')
) AS picks (user_id, interest_key)
WHERE NOT EXISTS (SELECT 1 FROM user_interests ui WHERE ui.user_id = picks.user_id::uuid);

INSERT INTO user_relations (source_user_id, target_user_id, type) VALUES
    ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000003', 'FOLLOW'),
    ('00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000004', 'FOLLOW'),
    ('00000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000002', 'FOLLOW')
ON CONFLICT DO NOTHING;
