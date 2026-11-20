-- One post per feed rule, for PostReadDbTest. Every title says what the post tests. All runs use the users below.
INSERT INTO users (id, username) VALUES
  ('a0000000-0000-0000-0000-000000000001', 'me'),
  ('a0000000-0000-0000-0000-000000000002', 'stranger'),
  ('a0000000-0000-0000-0000-000000000003', 'followed'),
  ('a0000000-0000-0000-0000-000000000004', 'i_blocked'),
  ('a0000000-0000-0000-0000-000000000005', 'blocked_me'),
  ('a0000000-0000-0000-0000-000000000006', 'friend'),
  ('a0000000-0000-0000-0000-000000000007', 'fan'),
  ('a0000000-0000-0000-0000-000000000008', 'quiet_friend'),
  ('a0000000-0000-0000-0000-000000000009', 'friend_2'),
  ('a0000000-0000-0000-0000-000000000010', 'friend_3');

UPDATE users SET share_stances = false WHERE id = 'a0000000-0000-0000-0000-000000000008';

-- I follow `followed` one way, `fan` follows me one way, and `friend`, `quiet_friend`, `friend_2` and `friend_3` are
-- mutual follows.

INSERT INTO user_relations (source_user_id, target_user_id, type) VALUES
  ('a0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000003', 'FOLLOW'),
  ('a0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000004', 'BLOCK'),
  ('a0000000-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000001', 'BLOCK'),
  ('a0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000006', 'FOLLOW'),
  ('a0000000-0000-0000-0000-000000000006', 'a0000000-0000-0000-0000-000000000001', 'FOLLOW'),
  ('a0000000-0000-0000-0000-000000000007', 'a0000000-0000-0000-0000-000000000001', 'FOLLOW'),
  ('a0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000008', 'FOLLOW'),
  ('a0000000-0000-0000-0000-000000000008', 'a0000000-0000-0000-0000-000000000001', 'FOLLOW'),
  ('a0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000009', 'FOLLOW'),
  ('a0000000-0000-0000-0000-000000000009', 'a0000000-0000-0000-0000-000000000001', 'FOLLOW'),
  ('a0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000010', 'FOLLOW'),
  ('a0000000-0000-0000-0000-000000000010', 'a0000000-0000-0000-0000-000000000001', 'FOLLOW');

CREATE TEMP TABLE fx (title text, author text, starts interval, ends interval, deleted boolean);
INSERT INTO fx VALUES
  ('future_public',            '2', '2 days',    NULL,        false),
  ('no_end_started_2h',        '2', '-2 hours',  NULL,        false),
  ('no_end_started_4h',        '2', '-4 hours',  NULL,        false),
  ('no_end_started_14h',       '2', '-14 hours', NULL,        false),
  ('no_end_started_16h',       '2', '-16 hours', NULL,        false),
  ('multi_day_running',        '2', '-7 days',   '7 days',    false),
  ('ended_1h_ago',             '2', '-3 hours',  '-1 hour',   false),
  ('ended_11h_ago',            '2', '-13 hours', '-11 hours', false),
  ('ended_13h_ago',            '2', '-15 hours', '-13 hours', false),
  ('ended_last_year',          '2', '-400 days', '-399 days', false),
  ('deleted',                  '2', '2 days',    NULL,        true),
  ('mine',                     '1', '2 days',    NULL,        false),
  ('liked',                    '2', '2 days',    NULL,        false),
  ('marked',                   '2', '2 days',    NULL,        false),
  ('interested',               '2', '2 days',    NULL,        false),
  ('attending',                '2', '2 days',    NULL,        false),
  ('commented',                '2', '2 days',    NULL,        false),
  ('by_i_blocked',             '4', '2 days',    NULL,        false),
  ('by_blocked_me',            '5', '2 days',    NULL,        false),
  ('by_followed',              '3', '2 days',    NULL,        false),
  ('by_fan',                   '7', '2 days',    NULL,        false),
  ('friend_going',             '2', '2 days',    NULL,        false),
  ('friend_interested',        '2', '2 days',    NULL,        false),
  ('friend_not_for_me',        '2', '2 days',    NULL,        false),
  ('followed_going',           '2', '2 days',    NULL,        false),
  ('fan_going',                '2', '2 days',    NULL,        false),
  ('quiet_friend_going',       '2', '2 days',    NULL,        false),
  ('friends_crowd',            '2', '2 days',    NULL,        false);

INSERT INTO posts (author_id, title, starts_at, ends_at, type, deleted_at)
SELECT ('a0000000-0000-0000-0000-00000000000' || author)::uuid, title, now() + starts, now() + ends, 'ONLINE',
       CASE WHEN deleted THEN now() END
FROM fx;

-- Created after the session's asOf: must not appear on later pages.
INSERT INTO posts (author_id, title, starts_at, type, created_at)
VALUES ('a0000000-0000-0000-0000-000000000002', 'created_after_as_of', now() + interval '2 days', 'ONLINE', now() + interval '1 hour');

-- ME's stances are made a minute back, so they're before any session a test starts (sessionStart() is 10 seconds
-- back). The like is ME's only vector: after the session's asOf, ME would have none.
INSERT INTO post_reactions (post_id, user_id, type, created_at)
SELECT id, 'a0000000-0000-0000-0000-000000000001', 'LIKE', now() - interval '1 minute'
FROM posts WHERE title = 'liked';

INSERT INTO post_participations (post_id, user_id, type, created_at)
SELECT id, 'a0000000-0000-0000-0000-000000000001',
       CASE title WHEN 'interested' THEN 'INTERESTED' WHEN 'attending' THEN 'TAKES_PART' ELSE 'NOT_INTERESTED' END,
       now() - interval '1 minute'
FROM posts WHERE title IN ('interested', 'attending', 'marked');

-- Other people's stances, for "Od obserwowanych i znajomych" and the friends line.
INSERT INTO post_participations (post_id, user_id, type)
SELECT p.id, ('a0000000-0000-0000-0000-00000000000' || s.who)::uuid, s.type
FROM posts p
JOIN (VALUES ('friend_going', '6', 'TAKES_PART'), ('friend_interested', '6', 'INTERESTED'),
             ('friend_not_for_me', '6', 'NOT_INTERESTED'), ('followed_going', '3', 'TAKES_PART'),
             ('fan_going', '7', 'TAKES_PART'), ('quiet_friend_going', '8', 'TAKES_PART')) AS s (title, who, type)
  ON s.title = p.title;

-- More friends than a card names, a minute apart, with the people who aren't friends mixed in.
INSERT INTO post_participations (post_id, user_id, type, created_at)
SELECT p.id, s.who::uuid, s.type, now() - s.ago::interval
FROM posts p
CROSS JOIN (VALUES ('a0000000-0000-0000-0000-000000000006', 'TAKES_PART', '3 minutes'),
                   ('a0000000-0000-0000-0000-000000000009', 'TAKES_PART', '2 minutes'),
                   ('a0000000-0000-0000-0000-000000000010', 'TAKES_PART', '1 minute'),
                   ('a0000000-0000-0000-0000-000000000003', 'INTERESTED', '1 minute'),
                   ('a0000000-0000-0000-0000-000000000007', 'INTERESTED', '1 minute'),
                   ('a0000000-0000-0000-0000-000000000008', 'INTERESTED', '1 minute')) AS s (who, type, ago)
WHERE p.title = 'friends_crowd';

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

-- Another post with walk_7's vector, so a like or a mark of walk_7 shows in its similarity. Created before the sessions
-- the tests start.
INSERT INTO posts (author_id, title, starts_at, type, created_at)
VALUES ('a0000000-0000-0000-0000-000000000002', 'twin_of_walk_7', now() + interval '2 days', 'ONLINE', now() - interval '1 minute');

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

-- Dates by overlap, at each edge of the window 2040-06-06 00:00 to 2040-06-07 23:59:59.999 (UTC), by their own author.
INSERT INTO users (id, username) VALUES ('a0000000-0000-0000-0000-000000000011', 'timekeeper');

INSERT INTO posts (author_id, title, starts_at, ends_at, open_ended, type)
SELECT 'a0000000-0000-0000-0000-000000000011', title, starts::timestamptz, ends::timestamptz, open_ended, 'ONLINE'
FROM (VALUES
  ('time_ends_at_from',          '2040-06-05 10:00Z',         '2040-06-06 00:00Z',  false),
  ('time_ends_before_from',      '2040-06-05 10:00Z',         '2040-06-05 23:59:59Z', false),
  ('time_starts_at_to',          '2040-06-07 23:59:59.999Z',  NULL,                 false),
  ('time_starts_after_to',       '2040-06-08 00:00Z',         NULL,                 false),
  ('time_spans_the_window',      '2040-06-01 10:00Z',         '2040-06-30 18:00Z',  false),
  ('time_default_end_inside',    '2040-06-05 22:00Z',         NULL,                 false),
  ('time_default_end_before',    '2040-06-05 20:59Z',         NULL,                 false),
  ('time_no_end_date_before',    '2039-01-01 10:00Z',         NULL,                 true),
  ('time_no_end_date_after',     '2040-07-01 10:00Z',         NULL,                 true)
) AS t(title, starts, ends, open_ended);

-- Nadchodzące's two streams, relative to now: nine dated events a day apart, and three already running. The description
-- "rytm" marks the dated ones and the running one that ends first.
INSERT INTO users (id, username) VALUES ('a0000000-0000-0000-0000-000000000012', 'rhythm');

INSERT INTO posts (author_id, title, description, starts_at, ends_at, type)
SELECT 'a0000000-0000-0000-0000-000000000012', 'rhythm_dated_' || g, 'rytm', date_trunc('minute', now()) + (g || ' days')::interval,
       NULL, 'ONLINE'
FROM generate_series(1, 9) g;

INSERT INTO posts (author_id, title, description, starts_at, ends_at, open_ended, type) VALUES
  ('a0000000-0000-0000-0000-000000000012', 'rhythm_running_1',    'rytm', now() - interval '1 day',   now() + interval '1 day',  false, 'ONLINE'),
  ('a0000000-0000-0000-0000-000000000012', 'rhythm_running_2',    NULL,   now() - interval '1 day',   now() + interval '2 days', false, 'ONLINE'),
  ('a0000000-0000-0000-0000-000000000012', 'rhythm_running_open', NULL,   now() - interval '10 days', NULL,                      true,  'ONLINE');
