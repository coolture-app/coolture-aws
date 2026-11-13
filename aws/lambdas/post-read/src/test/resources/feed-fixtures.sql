-- One post per feed rule, for PostReadDbTest. Every title says what the post tests. All runs use the users below.
INSERT INTO users (id, username) VALUES
  ('a0000000-0000-0000-0000-000000000001', 'me'),
  ('a0000000-0000-0000-0000-000000000002', 'stranger'),
  ('a0000000-0000-0000-0000-000000000003', 'followed'),
  ('a0000000-0000-0000-0000-000000000004', 'i_blocked'),
  ('a0000000-0000-0000-0000-000000000005', 'blocked_me');

INSERT INTO user_relations (source_user_id, target_user_id, type) VALUES
  ('a0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000003', 'FOLLOW'),
  ('a0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000004', 'BLOCK'),
  ('a0000000-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000001', 'BLOCK');

CREATE TEMP TABLE fx (title text, author text, starts interval, ends interval, vis text, deleted boolean);
INSERT INTO fx VALUES
  ('future_public',            '2', '2 days',    NULL,        'PUBLIC',  false),
  ('no_end_started_2h',        '2', '-2 hours',  NULL,        'PUBLIC',  false),
  ('no_end_started_4h',        '2', '-4 hours',  NULL,        'PUBLIC',  false),
  ('no_end_started_14h',       '2', '-14 hours', NULL,        'PUBLIC',  false),
  ('no_end_started_16h',       '2', '-16 hours', NULL,        'PUBLIC',  false),
  ('multi_day_running',        '2', '-7 days',   '7 days',    'PUBLIC',  false),
  ('ended_1h_ago',             '2', '-3 hours',  '-1 hour',   'PUBLIC',  false),
  ('ended_11h_ago',            '2', '-13 hours', '-11 hours', 'PUBLIC',  false),
  ('ended_13h_ago',            '2', '-15 hours', '-13 hours', 'PUBLIC',  false),
  ('ended_last_year',          '2', '-400 days', '-399 days', 'PUBLIC',  false),
  ('deleted',                  '2', '2 days',    NULL,        'PUBLIC',  true),
  ('mine',                     '1', '2 days',    NULL,        'PUBLIC',  false),
  ('mine_private',             '1', '2 days',    NULL,        'PRIVATE', false),
  ('liked',                    '2', '2 days',    NULL,        'PUBLIC',  false),
  ('disliked',                 '2', '2 days',    NULL,        'PUBLIC',  false),
  ('interested',               '2', '2 days',    NULL,        'PUBLIC',  false),
  ('attending',                '2', '2 days',    NULL,        'PUBLIC',  false),
  ('commented',                '2', '2 days',    NULL,        'PUBLIC',  false),
  ('by_i_blocked',             '4', '2 days',    NULL,        'PUBLIC',  false),
  ('by_blocked_me',            '5', '2 days',    NULL,        'PUBLIC',  false),
  ('friends_by_followed',      '3', '2 days',    NULL,        'FRIENDS', false),
  ('friends_by_stranger',      '2', '2 days',    NULL,        'FRIENDS', false),
  ('private_by_stranger',      '2', '2 days',    NULL,        'PRIVATE', false);

INSERT INTO posts (author_id, title, starts_at, ends_at, type, visibility, deleted_at)
SELECT ('a0000000-0000-0000-0000-00000000000' || author)::uuid, title, now() + starts, now() + ends, 'ONLINE', vis,
       CASE WHEN deleted THEN now() END
FROM fx;

-- Created after the session's asOf: must not appear on later pages.
INSERT INTO posts (author_id, title, starts_at, type, created_at)
VALUES ('a0000000-0000-0000-0000-000000000002', 'created_after_as_of', now() + interval '2 days', 'ONLINE', now() + interval '1 hour');

INSERT INTO post_reactions (post_id, user_id, type)
SELECT id, 'a0000000-0000-0000-0000-000000000001', CASE title WHEN 'liked' THEN 'LIKE' ELSE 'DISLIKE' END
FROM posts WHERE title IN ('liked', 'disliked');

INSERT INTO post_participations (post_id, user_id, type)
SELECT id, 'a0000000-0000-0000-0000-000000000001', CASE title WHEN 'interested' THEN 'INTERESTED' ELSE 'TAKES_PART' END
FROM posts WHERE title IN ('interested', 'attending');

INSERT INTO comments (post_id, author_id, content)
SELECT id, 'a0000000-0000-0000-0000-000000000001', 'x' FROM posts WHERE title = 'commented';

DROP TABLE fx;

-- Many posts sharing every sort key, so that a walk has to cross ties. Vectors make the feed query do its real work.
INSERT INTO posts (author_id, title, starts_at, type, positive_reaction_count, created_at)
SELECT 'a0000000-0000-0000-0000-000000000002', 'walk_' || g,
       date_trunc('hour', now()) + ((1 + g % 3) || ' days')::interval, 'ONLINE', g % 3,
       date_trunc('hour', now()) - ((g % 2) || ' hours')::interval
FROM generate_series(1, 60) g;

INSERT INTO post_embeddings (post_id, embedding, model)
SELECT p.id, (SELECT array_agg(sin(i * (1 + abs(hashtext(p.id::text)) % 1000) / 97.0))::real[]::vector FROM generate_series(1, 768) i), 'test'
FROM posts p
WHERE p.title LIKE 'walk\_%' AND p.author_id = 'a0000000-0000-0000-0000-000000000002';

-- The viewer's only vector comes from the post they liked, so the feed builds them a vector of this model.
INSERT INTO post_embeddings (post_id, embedding, model)
SELECT p.id, (SELECT array_agg(cos(i / 89.0))::real[]::vector FROM generate_series(1, 768) i), 'test'
FROM posts p WHERE p.title = 'liked' AND p.author_id = 'a0000000-0000-0000-0000-000000000002';

-- Another post with walk_7's vector, so a like of walk_7 shows in its similarity.
INSERT INTO posts (author_id, title, starts_at, type)
VALUES ('a0000000-0000-0000-0000-000000000002', 'twin_of_walk_7', now() + interval '2 days', 'ONLINE');

INSERT INTO post_embeddings (post_id, embedding, model)
SELECT (SELECT id FROM posts WHERE title = 'twin_of_walk_7'), pe.embedding, 'test'
FROM post_embeddings pe JOIN posts p ON p.id = pe.post_id WHERE p.title = 'walk_7';

-- Interests of their own, since the seed may have embedded the real ones. The stranger picks one and has no stances.
INSERT INTO interest_categories (key, label_pl, label_en, position) VALUES ('test_category', 't', 't', 99);
INSERT INTO interests (key, category_key, label_pl, label_en, embedding_text, position) VALUES
  ('test_interest_a', 'test_category', 'a', 'a', 'a', 0),
  ('test_interest_b', 'test_category', 'b', 'b', 'b', 1);
INSERT INTO interest_embeddings (interest_key, embedding, model, embedded_text)
SELECT key, (SELECT array_agg(sin(g * length(key) * position / 31.0 + position))::real[]::vector FROM generate_series(1, 768) g), 'test', key
FROM interests WHERE category_key = 'test_category';
INSERT INTO user_interests (user_id, interest_key) VALUES ('a0000000-0000-0000-0000-000000000002', 'test_interest_a');

-- A vector labelled with another model: comparing it with the viewer's would give a number that means nothing.
INSERT INTO posts (id, author_id, title, starts_at, type)
VALUES ('a0000000-0000-0000-0000-000000000200', 'a0000000-0000-0000-0000-000000000002', 'other_model_vector', now() + interval '2 days', 'ONLINE');

INSERT INTO post_embeddings (post_id, embedding, model)
SELECT 'a0000000-0000-0000-0000-000000000200', embedding, 'other-model' FROM post_embeddings LIMIT 1;

-- The map only shows posts with a location.
INSERT INTO event_locations (id, coordinates)
VALUES ('a0000000-0000-0000-0000-000000000100', ST_SetSRID(ST_MakePoint(18.65, 54.35), 4326)::geography);

INSERT INTO posts (author_id, event_location_id, title, starts_at, ends_at, type) VALUES
  ('a0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000100', 'map_open',            now() + interval '2 days',   NULL,                         'OFFLINE'),
  ('a0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000100', 'map_ended_last_year', now() - interval '400 days', now() - interval '399 days',  'OFFLINE'),
  ('a0000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000100', 'map_by_i_blocked',    now() + interval '2 days',   NULL,                         'OFFLINE'),
  ('a0000000-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000100', 'map_by_blocked_me',   now() + interval '2 days',   NULL,                         'OFFLINE');

-- My own post on the map, which excludeMine leaves out.
INSERT INTO posts (author_id, event_location_id, title, starts_at, type) VALUES
  ('a0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000100', 'map_mine', now() + interval '2 days', 'OFFLINE');

-- Places at three distances from (54.35, 18.65), for the radius filter and NEAREST, with ties at each distance.
INSERT INTO event_locations (id, city, venue_name, coordinates) VALUES
  ('a0000000-0000-0000-0000-000000000101', NULL, NULL, ST_SetSRID(ST_MakePoint(18.66, 54.35), 4326)::geography),
  ('a0000000-0000-0000-0000-000000000102', 'Fixturowo', 'Hala Fixturowa', ST_SetSRID(ST_MakePoint(18.70, 54.40), 4326)::geography);

INSERT INTO posts (author_id, event_location_id, title, starts_at, type)
SELECT 'a0000000-0000-0000-0000-000000000002', l.loc::uuid, 'near_' || l.name || '_' || g, now() + interval '3 days', 'OFFLINE'
FROM (VALUES ('a0000000-0000-0000-0000-000000000100', 'close'),
             ('a0000000-0000-0000-0000-000000000101', 'mid'),
             ('a0000000-0000-0000-0000-000000000102', 'far')) AS l(loc, name),
     generate_series(1, 8) g;

-- Ten photos on one post, with the cover last by position: a card shows the cover first, and at most 8.
INSERT INTO posts (id, author_id, title, starts_at, type)
VALUES ('a0000000-0000-0000-0000-000000000201', 'a0000000-0000-0000-0000-000000000002', 'ten_photos', now() + interval '2 days', 'ONLINE');

INSERT INTO media (id, owner_id, object_key, purpose, mime_type, status, width, height)
SELECT ('a0000000-0000-0000-0001-' || lpad(g::text, 12, '0'))::uuid, 'a0000000-0000-0000-0000-000000000002',
       'fixtures/photo_' || g, 'EVENT_MEDIA', 'image/jpeg', 'ATTACHED', 800, 600
FROM generate_series(1, 10) g;

INSERT INTO post_media (post_id, media_id, position)
SELECT 'a0000000-0000-0000-0000-000000000201', ('a0000000-0000-0000-0001-' || lpad(g::text, 12, '0'))::uuid, g % 10
FROM generate_series(1, 10) g;
