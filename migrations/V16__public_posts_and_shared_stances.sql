-- Every post is public. Posts saved as friends-only or private become public with the column.
ALTER TABLE posts DROP COLUMN visibility;

-- Whether friends (mutual follows) see this user's Interesuje mnie and Biorę udział.
ALTER TABLE users ADD COLUMN share_stances BOOLEAN NOT NULL DEFAULT true;
