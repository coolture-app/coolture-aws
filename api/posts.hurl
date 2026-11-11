# A post's life: Anna creates an online event, others read it, react and comment, and only she can change or delete it.
# Shapes and rules of the lists are in aws/lambdas/post-read/contracts/.

# Anna creates a post. The API answers with the whole post.
POST {{api}}/posts
X-User-Id: {{anna}}
X-User-Name: anna_demo
{
  "title": "Wieczór z planszówkami online",
  "description": "Gramy w planszówki przez przeglądarkę. Link wyślemy przed startem.",
  "eventUrl": "https://example.com/planszowki",
  "startsAt": "2030-01-15T18:00:00Z",
  "endsAt": "2030-01-15T21:00:00Z",
  "tags": ["planszówki", "online"],
  "type": "ONLINE",
  "visibility": "PUBLIC"
}
HTTP 201
[Captures]
post: jsonpath "$.id"
[Asserts]
jsonpath "$.author.username" == "anna_demo"
jsonpath "$.type" == "ONLINE"

# A guest reads it.
GET {{api}}/posts/{{post}}
X-Anonymous: true
HTTP 200
[Asserts]
jsonpath "$.title" == "Wieczór z planszówkami online"

# Jan can't edit someone else's post.
PATCH {{api}}/posts/{{post}}
X-User-Id: {{jan}}
X-User-Name: jan_demo
{ "title": "Mój tytuł" }
HTTP 403

# Anna can.
PATCH {{api}}/posts/{{post}}
X-User-Id: {{anna}}
X-User-Name: anna_demo
{ "title": "Wieczór z planszówkami online, edycja zimowa" }
HTTP 200
[Asserts]
jsonpath "$.title" == "Wieczór z planszówkami online, edycja zimowa"

# In person, a city and a pin are enough.
PATCH {{api}}/posts/{{post}}
X-User-Id: {{anna}}
X-User-Name: anna_demo
{ "type": "OFFLINE", "location": { "city": "Gdańsk", "coordinates": { "latitude": 54.35, "longitude": 18.65 } } }
HTTP 200
[Asserts]
jsonpath "$.location.city" == "Gdańsk"
jsonpath "$.location.id" not exists

# Back online, the place goes with it.
PATCH {{api}}/posts/{{post}}
X-User-Id: {{anna}}
X-User-Name: anna_demo
{ "type": "ONLINE" }
HTTP 200
[Asserts]
jsonpath "$.location" not exists

# Jan likes it and is interested. PUT replaces his reaction, so sending it twice changes nothing.
PUT {{api}}/posts/{{post}}/reaction
X-User-Id: {{jan}}
X-User-Name: jan_demo
{ "type": "LIKE" }
HTTP *
[Asserts]
status < 300

PUT {{api}}/posts/{{post}}/participation
X-User-Id: {{jan}}
X-User-Name: jan_demo
{ "type": "INTERESTED" }
HTTP *
[Asserts]
status < 300

GET {{api}}/posts/{{post}}
X-User-Id: {{jan}}
X-User-Name: jan_demo
HTTP 200
[Asserts]
jsonpath "$.positiveReactionCount" == 1

# Jan comments, edits his comment, and the comment shows on the post.
POST {{api}}/posts/{{post}}/comments
X-User-Id: {{jan}}
X-User-Name: jan_demo
{ "content": "Wchodzę!" }
HTTP 201
[Captures]
comment: jsonpath "$.id"

PATCH {{api}}/comments/{{comment}}
X-User-Id: {{jan}}
X-User-Name: jan_demo
{ "content": "Wchodzę, i biorę znajomych." }
HTTP 200

GET {{api}}/posts/{{post}}/comments
X-Anonymous: true
HTTP 200
[Asserts]
jsonpath "$.items[0].content" == "Wchodzę, i biorę znajomych."

# A guest can't comment.
POST {{api}}/posts/{{post}}/comments
X-Anonymous: true
{ "content": "Anonim" }
HTTP 401

# Jan takes it all back.
DELETE {{api}}/comments/{{comment}}
X-User-Id: {{jan}}
X-User-Name: jan_demo
HTTP *
[Asserts]
status < 300

DELETE {{api}}/posts/{{post}}/participation
X-User-Id: {{jan}}
X-User-Name: jan_demo
HTTP *
[Asserts]
status < 300

DELETE {{api}}/posts/{{post}}/reaction
X-User-Id: {{jan}}
X-User-Name: jan_demo
HTTP *
[Asserts]
status < 300

# Only Anna can delete her post. It's soft-deleted, so it's gone for readers.
DELETE {{api}}/posts/{{post}}
X-User-Id: {{jan}}
X-User-Name: jan_demo
HTTP 403

DELETE {{api}}/posts/{{post}}
X-User-Id: {{anna}}
X-User-Name: anna_demo
HTTP 204

GET {{api}}/posts/{{post}}
X-Anonymous: true
HTTP 404
