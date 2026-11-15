-- Where someone lives, for the feed's proximity when the browser gives no position: a city, town, village or district,
-- never an address. Private to its owner.

ALTER TABLE users
    ADD COLUMN home_name VARCHAR(128),
    ADD COLUMN home_location geography(point, 4326),
    ADD CONSTRAINT users_home_complete CHECK ((home_name IS NULL) = (home_location IS NULL));
