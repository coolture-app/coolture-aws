-- V22: Topics named like a kind of event leave onboarding, as the kind covers them. Their rows and vectors stay: the
-- survey stores respondents' picks as these keys. Only the Sport group's name changes, not what it holds.
UPDATE interests SET offered = false WHERE key IN ('concerts', 'cinema', 'parties', 'wellness');
UPDATE interest_categories SET label_pl = 'Aktywność fizyczna', label_en = 'Physical activity' WHERE key = 'sports';
