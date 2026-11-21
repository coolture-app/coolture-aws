# Profiles, following and interests, between the seed users. Everything changed here is changed back.

# Find people by name, and open a profile by username or id.
GET {{api}}/users?q=jan&limit=5
X-User-Id: {{anna}}
X-User-Name: anna_demo
HTTP 200
[Asserts]
jsonpath "$.items[0].username" == "jan_demo"
jsonpath "$.items[0].followsYou" == true

# Every word must be in one of the names, so a full name finds the person.
GET {{api}}/users?q=jan%20DEMO&limit=100
X-User-Id: {{anna}}
X-User-Name: anna_demo
HTTP 200
[Asserts]
jsonpath "$.items[*].username" contains "jan_demo"
jsonpath "$.items[*].username" not contains "anna_demo"

GET {{api}}/users/by-username/ola_demo
HTTP 200
[Captures]
ola_bio: jsonpath "$.bio"
[Asserts]
jsonpath "$.interests" exists

GET {{api}}/users/{{ola}}
X-User-Id: {{jan}}
X-User-Name: jan_demo
HTTP 200
[Asserts]
jsonpath "$.username" == "ola_demo"
jsonpath "$.isFollowing" == true

# followsYou is the other way round from isFollowing. Jan and Anna follow each other, so they're friends. Iza follows
# Anna, who doesn't follow her back.
GET {{api}}/users/{{jan}}
X-User-Id: {{anna}}
X-User-Name: anna_demo
HTTP 200
[Asserts]
jsonpath "$.isFollowing" == true
jsonpath "$.followsYou" == true

GET {{api}}/users/{{iza}}
X-User-Id: {{anna}}
X-User-Name: anna_demo
HTTP 200
[Asserts]
jsonpath "$.isFollowing" == false
jsonpath "$.followsYou" == true

GET {{api}}/users/by-username/anna_demo
X-User-Id: {{iza}}
X-User-Name: iza_demo
HTTP 200
[Asserts]
jsonpath "$.isFollowing" == true
jsonpath "$.followsYou" == false

GET {{api}}/users/{{anna}}/followers?limit=100
X-User-Id: {{anna}}
X-User-Name: anna_demo
HTTP 200
[Asserts]
jsonpath "$.items[?(@.username == 'iza_demo')].followsYou" == true

GET {{api}}/users/{{iza}}/following?limit=100
X-User-Id: {{iza}}
X-User-Name: iza_demo
HTTP 200
[Asserts]
jsonpath "$.items[?(@.username == 'anna_demo')].followsYou" == false

# Ola edits her bio, and nobody else can.
PATCH {{api}}/users/{{ola}}
X-User-Id: {{jan}}
X-User-Name: jan_demo
{ "bio": "Nie moje" }
HTTP 403

PATCH {{api}}/users/{{ola}}
X-User-Id: {{ola}}
X-User-Name: ola_demo
{ "bio": "Tymczasowe bio" }
HTTP 200
[Asserts]
jsonpath "$.bio" == "Tymczasowe bio"

PATCH {{api}}/users/{{ola}}
X-User-Id: {{ola}}
X-User-Name: ola_demo
{ "bio": "{{ola_bio}}" }
HTTP 200

# Ola follows Jan, he shows her among his followers, and she unfollows.
POST {{api}}/users/{{jan}}/follow
X-User-Id: {{ola}}
X-User-Name: ola_demo
HTTP *
[Asserts]
status < 300

GET {{api}}/users/{{jan}}/followers
X-User-Id: {{ola}}
X-User-Name: ola_demo
HTTP 200
[Asserts]
jsonpath "$.items[*].username" contains "ola_demo"

DELETE {{api}}/users/{{jan}}/follow
X-User-Id: {{ola}}
X-User-Name: ola_demo
HTTP *
[Asserts]
status < 300

# The catalogue: kinds of event, then interests by category. Interests with no event behind them, or named like a kind,
# are hidden.
GET {{api}}/dicts/interests
HTTP 200
[Asserts]
jsonpath "$.kinds" count == 15
jsonpath "$.minKinds" == 1
jsonpath "$.categories" count > 0
jsonpath "$.categories[*].interests[*].key" not contains "astrology"
jsonpath "$.categories[*].interests[*].key" not contains "concerts"

# PUT replaces both: at least 1 kind, and 0 to 6 interests. This sends Anna's seed picks back.
PUT {{api}}/users/{{anna}}/interests
X-User-Id: {{anna}}
X-User-Name: anna_demo
{ "kinds": ["concerts", "nightlife"], "interests": ["indie", "electronic", "clubbing", "music_festivals"] }
HTTP 200
[Asserts]
jsonpath "$.kinds" count == 2

PUT {{api}}/users/{{anna}}/interests
X-User-Id: {{anna}}
X-User-Name: anna_demo
{ "kinds": [], "interests": ["indie"] }
HTTP 400

# A hidden interest is refused.
PUT {{api}}/users/{{anna}}/interests
X-User-Id: {{anna}}
X-User-Name: anna_demo
{ "kinds": ["concerts"], "interests": ["astrology"] }
HTTP 400

# Anyone signed in can read someone's picks, for their profile. A guest can't.
GET {{api}}/users/{{anna}}/interests
X-User-Id: {{jan}}
X-User-Name: jan_demo
HTTP 200
[Asserts]
jsonpath "$.interests" contains "indie"
jsonpath "$.kinds" contains "nightlife"

GET {{api}}/users/{{anna}}/interests
X-Anonymous: true
HTTP 401

# Her profile carries the same picks, so the profile page needs no second request.
GET {{api}}/users/by-username/anna_demo
X-User-Id: {{jan}}
X-User-Name: jan_demo
HTTP 200
[Asserts]
jsonpath "$.interests" contains "indie"

# Ola says where she lives. Only she sees it: in /auth/me, never on her profile, and nobody else can change it.
PUT {{api}}/users/{{ola}}/home
X-User-Id: {{ola}}
X-User-Name: ola_demo
{ "name": "Oliwa, Gdańsk", "coordinates": { "latitude": 54.41123, "longitude": 18.56012 } }
HTTP 200
[Asserts]
jsonpath "$.name" == "Oliwa, Gdańsk"
jsonpath "$.coordinates.latitude" == 54.41

GET {{api}}/auth/me
X-User-Id: {{ola}}
X-User-Name: ola_demo
HTTP 200
[Asserts]
jsonpath "$.home.name" == "Oliwa, Gdańsk"
jsonpath "$.interests" contains "galleries"
jsonpath "$.home.coordinates.longitude" == 18.56

GET {{api}}/users/{{ola}}
X-User-Id: {{jan}}
X-User-Name: jan_demo
HTTP 200
[Asserts]
jsonpath "$.home" not exists

PUT {{api}}/users/{{ola}}/home
X-User-Id: {{jan}}
X-User-Name: jan_demo
{ "name": "Gdynia", "coordinates": { "latitude": 54.52, "longitude": 18.54 } }
HTTP 403

PUT {{api}}/users/{{ola}}/home
X-User-Id: {{ola}}
X-User-Name: ola_demo
{ "name": "Gdynia" }
HTTP 400

DELETE {{api}}/users/{{ola}}/home
X-User-Id: {{ola}}
X-User-Name: ola_demo
HTTP 204

GET {{api}}/auth/me
X-User-Id: {{ola}}
X-User-Name: ola_demo
HTTP 200
[Asserts]
jsonpath "$.home" not exists

# Anna blocks Iza, who follows her. A block works both ways and doesn't say who blocked whom: Iza gets 404 for Anna's
# profile and her posts, and doesn't find her in search. Anna gets a slim profile of Iza, to unblock her from there.
POST {{api}}/posts
X-User-Id: {{anna}}
X-User-Name: anna_demo
{ "title": "Spacer po Oliwie", "description": "Zbiórka przy katedrze.", "startsAt": "2030-02-01T10:00:00Z", "type": "ONLINE" }
HTTP 201
[Captures]
annas_post: jsonpath "$.id"

POST {{api}}/users/{{iza}}/block
X-User-Id: {{anna}}
X-User-Name: anna_demo
HTTP 204

GET {{api}}/users/{{anna}}
X-User-Id: {{iza}}
X-User-Name: iza_demo
HTTP 404

GET {{api}}/users?q=anna_demo
X-User-Id: {{iza}}
X-User-Name: iza_demo
HTTP 200
[Asserts]
jsonpath "$.items" count == 0

GET {{api}}/posts/{{annas_post}}
X-User-Id: {{iza}}
X-User-Name: iza_demo
HTTP 404

PUT {{api}}/posts/{{annas_post}}/reaction
X-User-Id: {{iza}}
X-User-Name: iza_demo
{ "type": "LIKE" }
HTTP 404

GET {{api}}/users/{{iza}}
X-User-Id: {{anna}}
X-User-Name: anna_demo
HTTP 200
[Asserts]
jsonpath "$.isBlocked" == true
jsonpath "$.username" == "iza_demo"
jsonpath "$.followersCount" not exists
jsonpath "$.interests" not exists

# Unblocking brings the profile back, but not Iza's follow, which she makes again.
DELETE {{api}}/users/{{iza}}/block
X-User-Id: {{anna}}
X-User-Name: anna_demo
HTTP 204

GET {{api}}/users/{{anna}}
X-User-Id: {{iza}}
X-User-Name: iza_demo
HTTP 200
[Asserts]
jsonpath "$.isFollowing" == false

POST {{api}}/users/{{anna}}/follow
X-User-Id: {{iza}}
X-User-Name: iza_demo
HTTP *
[Asserts]
status < 300

DELETE {{api}}/posts/{{annas_post}}
X-User-Id: {{anna}}
X-User-Name: anna_demo
HTTP 204
