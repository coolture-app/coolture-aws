-- Country codes are two letters, as the geocoder gives them, and any code is accepted, so the dictionary goes.

ALTER TABLE event_locations DROP CONSTRAINT event_locations_country_code_fkey;
DROP TABLE country_codes;

UPDATE event_locations SET country_code = CASE country_code
    WHEN 'POL' THEN 'PL' WHEN 'DEU' THEN 'DE' WHEN 'CZE' THEN 'CZ' WHEN 'SVK' THEN 'SK' WHEN 'UKR' THEN 'UA'
    WHEN 'LTU' THEN 'LT' WHEN 'BLR' THEN 'BY' WHEN 'USA' THEN 'US' WHEN 'GBR' THEN 'GB' WHEN 'FRA' THEN 'FR'
    WHEN 'ITA' THEN 'IT' WHEN 'ESP' THEN 'ES' WHEN 'NLD' THEN 'NL'
END
WHERE length(country_code) = 3;

ALTER TABLE event_locations ALTER COLUMN country_code TYPE VARCHAR(2);

-- Rows no post points at: left by posts switched to online, or made through the removed /event-locations routes.
DELETE FROM event_locations el WHERE NOT EXISTS (SELECT 1 FROM posts p WHERE p.event_location_id = el.id);
