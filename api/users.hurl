# Profiles, following and interests, between the seed users. Everything changed here is changed back.

# Find people by name, and open a profile by username or id.
GET {{api}}/users?q=jan&limit=5
X-User-Id: {{anna}}
X-User-Name: anna_demo
HTTP 200
[Asserts]
jsonpath "$.items[0].username" == "jan_demo"

GET {{api}}/users/by-username/ola_demo
HTTP 200
[Captures]
ola_bio: jsonpath "$.bio"

GET {{api}}/users/{{ola}}
X-User-Id: {{jan}}
X-User-Name: jan_demo
HTTP 200
[Asserts]
jsonpath "$.username" == "ola_demo"
jsonpath "$.isFollowing" == true

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

GET {{api}}/users/{{anna}}/interests
X-User-Id: {{anna}}
X-User-Name: anna_demo
HTTP 200
[Asserts]
jsonpath "$.interests" contains "concerts"
