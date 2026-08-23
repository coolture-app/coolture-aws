-- V2: Drop event_categories table and FK (categories now managed in application code)

-- This migration is a no-op for new installations.
-- The event_categories table was removed in a previous iteration.
-- Category is now a UUID FK to an in-memory/dictionary table managed by the app.
