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

INSERT INTO user_embeddings (user_id, embedding, model)
SELECT 'a0000000-0000-0000-0000-000000000001', array_agg(cos(i / 89.0))::real[]::vector, 'test' FROM generate_series(1, 768) i;

-- The map only shows posts with a location.
INSERT INTO event_locations (id, coordinates)
VALUES ('a0000000-0000-0000-0000-000000000100', ST_SetSRID(ST_MakePoint(18.65, 54.35), 4326)::geography);

INSERT INTO posts (author_id, event_location_id, title, starts_at, ends_at, type) VALUES
  ('a0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000100', 'map_open',            now() + interval '2 days',   NULL,                         'OFFLINE'),
  ('a0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000100', 'map_ended_last_year', now() - interval '400 days', now() - interval '399 days',  'OFFLINE'),
  ('a0000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000100', 'map_by_i_blocked',    now() + interval '2 days',   NULL,                         'OFFLINE'),
  ('a0000000-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000100', 'map_by_blocked_me',   now() + interval '2 days',   NULL,                         'OFFLINE');
