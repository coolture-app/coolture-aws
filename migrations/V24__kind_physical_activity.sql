-- V24: The Sport kind is named Aktywność fizyczna, as its topic group is (V22). Only the label changes: its texts, and
-- so its vector and every post's kinds, stay.
UPDATE event_kinds SET label_pl = 'Aktywność fizyczna', label_en = 'Physical activity' WHERE key = 'sports';
