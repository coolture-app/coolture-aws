-- V25: The kind and the topic group `sports` are both named Aktywność i sport. Only labels change.
UPDATE event_kinds SET label_pl = 'Aktywność i sport', label_en = 'Activity and sport' WHERE key = 'sports';
UPDATE interest_categories SET label_pl = 'Aktywność i sport', label_en = 'Activity and sport' WHERE key = 'sports';
