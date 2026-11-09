-- The image's size in pixels, so a post's photo frame can take the photo's shape before it loads.
-- Null for media uploaded before this, and for files that aren't images.
ALTER TABLE media
    ADD COLUMN width INTEGER CHECK (width > 0),
    ADD COLUMN height INTEGER CHECK (height > 0);
