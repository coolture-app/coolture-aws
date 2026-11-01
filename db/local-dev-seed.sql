-- Local development seed data
-- This file is ONLY applied by the local `migrate` compose service and NEVER deployed to AWS.

INSERT INTO users (id, username, first_name, last_name, bio) VALUES
    ('00000000-0000-0000-0000-000000000001', 'local_dev', 'Local', 'Developer', 'Default local development user')
ON CONFLICT (id) DO NOTHING;

-- Demo data for the recommender: three users with interests, six upcoming events in Tricity, follows and likes.

INSERT INTO users (id, username, first_name, last_name, bio) VALUES
    ('00000000-0000-0000-0000-000000000002', 'anna_demo', 'Anna', 'Demo', 'Lubię salsę i tańce latynoamerykańskie'),
    ('00000000-0000-0000-0000-000000000003', 'jan_demo', 'Jan', 'Demo', 'Lubię jazz i teatr'),
    ('00000000-0000-0000-0000-000000000004', 'ola_demo', 'Ola', 'Demo', 'Lubię metal i fotografię')
ON CONFLICT (id) DO NOTHING;

INSERT INTO event_locations (id, country_code, venue_name, city, coordinates) VALUES
    ('00000000-0000-0000-0000-000000000201', 'POL', 'Miejsce demonstracyjne', 'Gdańsk', ST_SetSRID(ST_MakePoint(18.605, 54.381), 4326)::geography),
    ('00000000-0000-0000-0000-000000000202', 'POL', 'Miejsce demonstracyjne', 'Sopot', ST_SetSRID(ST_MakePoint(18.560, 54.441), 4326)::geography),
    ('00000000-0000-0000-0000-000000000203', 'POL', 'Miejsce demonstracyjne', 'Gdynia', ST_SetSRID(ST_MakePoint(18.539, 54.519), 4326)::geography)
ON CONFLICT (id) DO NOTHING;

INSERT INTO posts (id, author_id, event_location_id, title, description, tags, starts_at, ends_at, type) VALUES
    ('00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000201',
     'Wieczór salsy w Gdańsku', 'Taniec latynoamerykański, salsa i bachata dla początkujących.', ARRAY['taniec', 'salsa'],
     now() + interval '14 days', now() + interval '14 days 3 hours', 'OFFLINE'),
    ('00000000-0000-0000-0000-000000000102', '00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000201',
     'Jazz w klubie Żak', 'Koncert kwartetu jazzowego. Improwizacja i muzyka na żywo.', ARRAY['jazz', 'koncert'],
     now() + interval '14 days', now() + interval '14 days 3 hours', 'OFFLINE'),
    ('00000000-0000-0000-0000-000000000103', '00000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000203',
     'Koncert metalowy w Gdyni', 'Ciężkie gitarowe brzmienia i koncert zespołów metalowych.', ARRAY['metal', 'koncert'],
     now() + interval '14 days', now() + interval '14 days 3 hours', 'OFFLINE'),
    ('00000000-0000-0000-0000-000000000104', '00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000201',
     'Warsztaty robienia sushi', 'Przygotowanie ryżu, krojenie ryb i zwijanie rolek sushi.', ARRAY['kuchnia', 'warsztaty'],
     now() + interval '14 days', now() + interval '14 days 3 hours', 'OFFLINE'),
    ('00000000-0000-0000-0000-000000000105', '00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000202',
     'Wieczór teatralny w Sopocie', 'Spektakl współczesny i rozmowa z aktorami.', ARRAY['teatr', 'sztuka'],
     now() + interval '14 days', now() + interval '14 days 3 hours', 'OFFLINE'),
    ('00000000-0000-0000-0000-000000000106', '00000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000201',
     'Plener fotograficzny', 'Spacer fotograficzny, nauka kadrowania i fotografii ulicznej.', ARRAY['fotografia', 'warsztaty'],
     now() + interval '14 days', now() + interval '14 days 3 hours', 'OFFLINE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO user_relations (source_user_id, target_user_id, type) VALUES
    ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000003', 'FOLLOW'),
    ('00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000004', 'FOLLOW'),
    ('00000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000002', 'FOLLOW')
ON CONFLICT DO NOTHING;

INSERT INTO post_reactions (post_id, user_id, type) VALUES
    ('00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000002', 'LIKE'),
    ('00000000-0000-0000-0000-000000000102', '00000000-0000-0000-0000-000000000003', 'LIKE'),
    ('00000000-0000-0000-0000-000000000103', '00000000-0000-0000-0000-000000000004', 'LIKE')
ON CONFLICT DO NOTHING;

UPDATE posts p SET
    positive_reaction_count = (SELECT count(*) FROM post_reactions r WHERE r.post_id = p.id AND r.type = 'LIKE'),
    negative_reaction_count = (SELECT count(*) FROM post_reactions r WHERE r.post_id = p.id AND r.type = 'DISLIKE'),
    participant_count = (SELECT count(*) FROM post_participations r WHERE r.post_id = p.id)
WHERE p.id IN (
    '00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000102', '00000000-0000-0000-0000-000000000103',
    '00000000-0000-0000-0000-000000000104', '00000000-0000-0000-0000-000000000105', '00000000-0000-0000-0000-000000000106'
);
