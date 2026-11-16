# Profiles, following and interests, between the seed users. Everything changed here is changed back.

# Find people by name, and open a profile by username or id.
GET {{api}}/users?q=jan&limit=5
X-User-Id: {{anna}}
X-User-Name: anna_demo
HTTP 200
[Asserts]
jsonpath "$.items[0].username" == "jan_demo"
jsonpath "$.items[0].followsYou" == true

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
jsonpath "$.items[?(@.username == 'iza_demo')].followsYou" contains true

GET {{api}}/users/{{iza}}/following?limit=100
X-User-Id: {{iza}}
X-User-Name: iza_demo
HTTP 200
[Asserts]
jsonpath "$.items[?(@.username == 'anna_demo')].followsYou" contains false

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

# The interest catalogue, and a user's picks. PUT replaces the whole selection (3 to 6 interests).
GET {{api}}/dicts/interests
HTTP 200
[Asserts]
jsonpath "$.categories" count > 0

# Anyone signed in can read someone's picks, for their profile. A guest can't.
GET {{api}}/users/{{anna}}/interests
X-User-Id: {{jan}}
X-User-Name: jan_demo
HTTP 200
[Asserts]
jsonpath "$.interests" contains "concerts"

GET {{api}}/users/{{anna}}/interests
X-Anonymous: true
HTTP 401

# Her profile carries the same picks, so the profile page needs no second request.
GET {{api}}/users/by-username/anna_demo
X-User-Id: {{jan}}
X-User-Name: jan_demo
HTTP 200
[Asserts]
jsonpath "$.interests" contains "concerts"

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
