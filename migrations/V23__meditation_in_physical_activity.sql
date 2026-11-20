-- V23: Medytacja joins Aktywność fizyczna beside Joga and Pilates. With Wellness hidden (V22) it was the only topic
-- Styl życia offered. Not only a label: a pick adds 0.25 of its group's mean, so the move changes the taste of anyone
-- holding a topic in either group.
UPDATE interests SET category_key = 'sports', position = 3 WHERE key = 'meditation';
