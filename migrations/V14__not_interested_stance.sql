-- "Nie dla mnie" is a private stance about the viewer's taste, beside interested and going, so a dislike becomes one. A post
-- already saved or attended keeps that stance. Nothing counts these marks publicly.

ALTER TABLE post_participations DROP CONSTRAINT post_participations_type_check;
ALTER TABLE post_participations ADD CONSTRAINT post_participations_type_check
    CHECK (type IN ('INTERESTED', 'TAKES_PART', 'NOT_INTERESTED'));

INSERT INTO post_participations (post_id, user_id, type, created_at)
SELECT post_id, user_id, 'NOT_INTERESTED', created_at
FROM post_reactions
WHERE type = 'DISLIKE'
ON CONFLICT (post_id, user_id) DO NOTHING;

DELETE FROM post_reactions WHERE type = 'DISLIKE';

ALTER TABLE post_reactions DROP CONSTRAINT post_reactions_type_check;
ALTER TABLE post_reactions ADD CONSTRAINT post_reactions_type_check CHECK (type = 'LIKE');

ALTER TABLE posts DROP COLUMN negative_reaction_count;
