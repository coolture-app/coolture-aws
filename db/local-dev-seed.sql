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

-- Three regular users with a history: their reactions come from `make seed-reactions`, because the events they react
-- to are loaded later, by `make seed-events`.
INSERT INTO users (id, username, first_name, last_name, bio) VALUES
    ('00000000-0000-0000-0000-000000000002', 'anna_demo', 'Anna', 'Demo', 'Koncerty, indie i elektronika, a w weekendy kluby'),
    ('00000000-0000-0000-0000-000000000003', 'jan_demo', 'Jan', 'Demo', 'Teatr, stand-up i kino autorskie'),
    ('00000000-0000-0000-0000-000000000004', 'ola_demo', 'Ola', 'Demo', 'Galerie, fotografia, bieganie i dobre jedzenie')
ON CONFLICT (id) DO NOTHING;

-- Anna and Jan follow each other, so they're friends and see each other's stances. Ola and Jan follow one way.
INSERT INTO user_relations (source_user_id, target_user_id, type) VALUES
    ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000003', 'FOLLOW'),
    ('00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000002', 'FOLLOW'),
    ('00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000004', 'FOLLOW'),
    ('00000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000002', 'FOLLOW')
ON CONFLICT DO NOTHING;

-- Friends for Anna, Jan and Ola, so a card's friends line can be seen in every shape. `make seed-friends` gives them
-- stances and a few of them photos. Bartek is a friend who doesn't share his stances, and Iza follows the three without
-- being followed back, so neither shows on a card.
INSERT INTO users (id, username, first_name, last_name, share_stances) VALUES
    ('00000000-0000-0000-0000-000000000005', 'kasia_demo', 'Kasia', 'Kowalska', true),
    ('00000000-0000-0000-0000-000000000006', 'piotr_demo', 'Piotr', 'Nowak', true),
    ('00000000-0000-0000-0000-000000000007', 'ewa_demo', 'Ewa', 'Wiśniewska', true),
    ('00000000-0000-0000-0000-000000000008', 'tomek_demo', 'Tomek', 'Wójcik', true),
    ('00000000-0000-0000-0000-000000000009', 'zofia_demo', 'Zofia', 'Kamińska', true),
    ('00000000-0000-0000-0000-000000000010', 'marek_demo', 'Marek', 'Lewandowski', true),
    ('00000000-0000-0000-0000-000000000011', 'magda_demo', 'Magda', 'Zielińska', true),
    ('00000000-0000-0000-0000-000000000012', 'bartek_demo', 'Bartek', 'Mazur', false),
    ('00000000-0000-0000-0000-000000000013', 'iza_demo', 'Iza', 'Krawczyk', true)
ON CONFLICT (id) DO NOTHING;

-- Each demo account is a persona, so a feed can be tried for a taste: Anna indie and clubs, Jan theatre and arthouse
-- film, Ola galleries, running and food, Kasia rock, Piotr classical music and opera, Ewa a parent (children's
-- workshops, fairs), Tomek games and sci-fi, Zofia history talks and walks, Marek tech talks and causes, Magda dancing
-- and nightlife, Bartek kinds only (sport, outdoors), Iza yoga and crafts. Together they cover every kind. local_dev has
-- none, for onboarding. Only offered topics, at most 6. Each set goes in only for a user with none yet, so a choice made
-- in the app survives the next `make up`.
INSERT INTO user_interests (user_id, interest_key)
SELECT picks.user_id::uuid, picks.interest_key
FROM (VALUES
    ('00000000-0000-0000-0000-000000000002', 'indie'),
    ('00000000-0000-0000-0000-000000000002', 'electronic'),
    ('00000000-0000-0000-0000-000000000002', 'clubbing'),
    ('00000000-0000-0000-0000-000000000002', 'music_festivals'),
    ('00000000-0000-0000-0000-000000000003', 'theatre'),
    ('00000000-0000-0000-0000-000000000003', 'stand_up'),
    ('00000000-0000-0000-0000-000000000003', 'arthouse'),
    ('00000000-0000-0000-0000-000000000003', 'author_meetings'),
    ('00000000-0000-0000-0000-000000000004', 'galleries'),
    ('00000000-0000-0000-0000-000000000004', 'museums'),
    ('00000000-0000-0000-0000-000000000004', 'photography'),
    ('00000000-0000-0000-0000-000000000004', 'running'),
    ('00000000-0000-0000-0000-000000000004', 'foodie'),
    ('00000000-0000-0000-0000-000000000004', 'walks'),
    ('00000000-0000-0000-0000-000000000005', 'rock'),
    ('00000000-0000-0000-0000-000000000005', 'metal'),
    ('00000000-0000-0000-0000-000000000005', 'craft_beer'),
    ('00000000-0000-0000-0000-000000000006', 'classical_music'),
    ('00000000-0000-0000-0000-000000000006', 'opera'),
    ('00000000-0000-0000-0000-000000000006', 'ballet'),
    ('00000000-0000-0000-0000-000000000007', 'family_events'),
    ('00000000-0000-0000-0000-000000000007', 'diy'),
    ('00000000-0000-0000-0000-000000000007', 'markets'),
    ('00000000-0000-0000-0000-000000000008', 'board_games'),
    ('00000000-0000-0000-0000-000000000008', 'rpg'),
    ('00000000-0000-0000-0000-000000000008', 'sci_fi'),
    ('00000000-0000-0000-0000-000000000008', 'pub_quiz'),
    ('00000000-0000-0000-0000-000000000009', 'history'),
    ('00000000-0000-0000-0000-000000000009', 'architecture'),
    ('00000000-0000-0000-0000-000000000009', 'walks'),
    ('00000000-0000-0000-0000-000000000009', 'science'),
    ('00000000-0000-0000-0000-000000000010', 'technology'),
    ('00000000-0000-0000-0000-000000000010', 'business'),
    ('00000000-0000-0000-0000-000000000010', 'volunteering'),
    ('00000000-0000-0000-0000-000000000010', 'ecology'),
    ('00000000-0000-0000-0000-000000000011', 'salsa'),
    ('00000000-0000-0000-0000-000000000011', 'cocktails'),
    ('00000000-0000-0000-0000-000000000011', 'hip_hop'),
    ('00000000-0000-0000-0000-000000000013', 'yoga'),
    ('00000000-0000-0000-0000-000000000013', 'meditation'),
    ('00000000-0000-0000-0000-000000000013', 'ceramics'),
    ('00000000-0000-0000-0000-000000000013', 'vegan'),
    ('00000000-0000-0000-0000-000000000013', 'tea')
) AS picks (user_id, interest_key)
WHERE NOT EXISTS (SELECT 1 FROM user_interests ui WHERE ui.user_id = picks.user_id::uuid)
  AND NOT EXISTS (SELECT 1 FROM user_kinds uk WHERE uk.user_id = picks.user_id::uuid);

INSERT INTO user_kinds (user_id, kind_key)
SELECT picks.user_id::uuid, picks.kind_key
FROM (VALUES
    ('00000000-0000-0000-0000-000000000002', 'concerts'),
    ('00000000-0000-0000-0000-000000000002', 'nightlife'),
    ('00000000-0000-0000-0000-000000000003', 'stage'),
    ('00000000-0000-0000-0000-000000000003', 'cinema'),
    ('00000000-0000-0000-0000-000000000004', 'exhibitions'),
    ('00000000-0000-0000-0000-000000000004', 'sports'),
    ('00000000-0000-0000-0000-000000000004', 'food'),
    ('00000000-0000-0000-0000-000000000005', 'concerts'),
    ('00000000-0000-0000-0000-000000000006', 'concerts'),
    ('00000000-0000-0000-0000-000000000006', 'stage'),
    ('00000000-0000-0000-0000-000000000007', 'kids'),
    ('00000000-0000-0000-0000-000000000007', 'workshops'),
    ('00000000-0000-0000-0000-000000000007', 'fairs'),
    ('00000000-0000-0000-0000-000000000008', 'games'),
    ('00000000-0000-0000-0000-000000000008', 'cinema'),
    ('00000000-0000-0000-0000-000000000009', 'talks'),
    ('00000000-0000-0000-0000-000000000009', 'outdoors'),
    ('00000000-0000-0000-0000-000000000010', 'talks'),
    ('00000000-0000-0000-0000-000000000010', 'causes'),
    ('00000000-0000-0000-0000-000000000011', 'nightlife'),
    ('00000000-0000-0000-0000-000000000011', 'workshops'),
    ('00000000-0000-0000-0000-000000000012', 'sports'),
    ('00000000-0000-0000-0000-000000000012', 'outdoors'),
    ('00000000-0000-0000-0000-000000000013', 'wellbeing'),
    ('00000000-0000-0000-0000-000000000013', 'workshops'),
    ('00000000-0000-0000-0000-000000000013', 'food')
) AS picks(user_id, kind_key)
WHERE NOT EXISTS (SELECT 1 FROM user_kinds uk WHERE uk.user_id = picks.user_id::uuid);

INSERT INTO user_relations (source_user_id, target_user_id, type)
SELECT pair.source_user_id::uuid, pair.target_user_id::uuid, 'FOLLOW'
FROM (VALUES ('00000000-0000-0000-0000-000000000002'), ('00000000-0000-0000-0000-000000000003'),
             ('00000000-0000-0000-0000-000000000004')) AS viewer (id)
CROSS JOIN (VALUES ('00000000-0000-0000-0000-000000000005'), ('00000000-0000-0000-0000-000000000006'),
                   ('00000000-0000-0000-0000-000000000007'), ('00000000-0000-0000-0000-000000000008'),
                   ('00000000-0000-0000-0000-000000000009'), ('00000000-0000-0000-0000-000000000010'),
                   ('00000000-0000-0000-0000-000000000011'), ('00000000-0000-0000-0000-000000000012'))
    AS friend (id)
CROSS JOIN LATERAL (VALUES (viewer.id, friend.id), (friend.id, viewer.id)) AS pair (source_user_id, target_user_id)
UNION ALL
SELECT '00000000-0000-0000-0000-000000000013'::uuid, viewer.id::uuid, 'FOLLOW'
FROM (VALUES ('00000000-0000-0000-0000-000000000002'), ('00000000-0000-0000-0000-000000000003'),
             ('00000000-0000-0000-0000-000000000004')) AS viewer (id)
ON CONFLICT DO NOTHING;

-- A block made in the app ends follows both ways, so the follows above don't come back between a blocked pair.
DELETE FROM user_relations follow
USING user_relations blk
WHERE follow.type = 'FOLLOW' AND blk.type = 'BLOCK'
  AND ((blk.source_user_id = follow.source_user_id AND blk.target_user_id = follow.target_user_id)
    OR (blk.source_user_id = follow.target_user_id AND blk.target_user_id = follow.source_user_id));

-- Jan lives in Gdynia, so his feed shows the home fallback when the browser gives no position. Only while he has none:
-- a home changed in the app survives the next `make up`, and a removed one comes back.
UPDATE users
SET home_name = 'Gdynia', home_location = ST_SetSRID(ST_MakePoint(18.54, 54.52), 4326)::geography
WHERE id = '00000000-0000-0000-0000-000000000003' AND home_name IS NULL;
