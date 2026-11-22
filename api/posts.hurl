# A post's life: Anna creates an online event, others read it, react and comment, and only she can change or delete it.
# Shapes and rules of the lists are in aws/lambdas/post-read/contracts/.

# Anna creates a post. The API answers with the whole post.
POST {{api}}/posts
X-User-Id: {{anna}}
X-User-Name: anna_demo
{
  "title": "Wieczór z planszówkami online",
  "description": "Gramy w planszówki przez przeglądarkę. Link wyślemy przed startem.",
  "links": [{ "url": "https://example.com/planszowki", "label": "  Zasady  " }, { "url": "https://example.org" }],
  "startsAt": "2030-01-15T18:00:00Z",
  "endsAt": "2030-01-15T21:00:00Z",
  "type": "ONLINE"
}
HTTP 201
[Captures]
post: jsonpath "$.id"
[Asserts]
jsonpath "$.author.username" == "anna_demo"
jsonpath "$.type" == "ONLINE"
jsonpath "$.links[0].label" == "Zasady"
jsonpath "$.links[1].label" not exists

# Search ignores diacritics and word forms, and needs every word, in any order and in any field.
GET {{api}}/posts?authorId={{anna}}&q=WIECZOR%20planszowkami%20przegladarke&limit=100
X-Anonymous: true
HTTP 200
[Asserts]
jsonpath "$.items[*].id" contains "{{post}}"

GET {{api}}/posts?authorId={{anna}}&q=wieczor%20szachy&limit=100
X-Anonymous: true
HTTP 200
[Asserts]
jsonpath "$.items[*].id" not contains "{{post}}"

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

# PATCH follows merge-patch rules: a field left out keeps its value, and null removes it.
PATCH {{api}}/posts/{{post}}
X-User-Id: {{anna}}
X-User-Name: anna_demo
{ "endsAt": null, "links": null }
HTTP 200
[Asserts]
jsonpath "$.endsAt" not exists
jsonpath "$.links" not exists
jsonpath "$.openEnded" == false
jsonpath "$.title" == "Wieczór z planszówkami online, edycja zimowa"

# No end date and an end at once is a contradiction.
PATCH {{api}}/posts/{{post}}
X-User-Id: {{anna}}
X-User-Name: anna_demo
{ "openEnded": true, "endsAt": "2030-01-15T21:00:00Z" }
HTTP 400

# With no end date, the post runs in every later window.
PATCH {{api}}/posts/{{post}}
X-User-Id: {{anna}}
X-User-Name: anna_demo
{ "openEnded": true }
HTTP 200
[Asserts]
jsonpath "$.openEnded" == true
jsonpath "$.endsAt" not exists

GET {{api}}/posts?authorId={{anna}}&from=2031-06-06T00:00:00Z&to=2031-06-07T23:59:59Z&limit=100
X-Anonymous: true
HTTP 200
[Asserts]
jsonpath "$.items[?(@.id == '{{post}}')].openEnded" == true

# An end turns it off again.
PATCH {{api}}/posts/{{post}}
X-User-Id: {{anna}}
X-User-Name: anna_demo
{ "endsAt": "2030-01-15T21:00:00Z" }
HTTP 200
[Asserts]
jsonpath "$.openEnded" == false
jsonpath "$.endsAt" == "2030-01-15T21:00:00Z"

GET {{api}}/posts?authorId={{anna}}&from=2031-06-06T00:00:00Z&to=2031-06-07T23:59:59Z&limit=100
X-Anonymous: true
HTTP 200
[Asserts]
jsonpath "$.items[?(@.id == '{{post}}')]" not exists

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

# Anna and Jan follow each other, so she sees him among her friends who are interested. Iza follows Jan, but he doesn't
# follow her back, so she sees nobody, and nor does a guest.
GET {{api}}/posts/{{post}}/friends
X-User-Id: {{anna}}
X-User-Name: anna_demo
HTTP 200
[Asserts]
jsonpath "$.interested[0].username" == "jan_demo"
jsonpath "$.going" count == 0

GET {{api}}/posts/{{post}}/friends
X-User-Id: {{iza}}
X-User-Name: iza_demo
HTTP 200
[Asserts]
jsonpath "$.interested" count == 0

GET {{api}}/posts/{{post}}/friends
X-Anonymous: true
HTTP 200
[Asserts]
jsonpath "$.interested" count == 0

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

# Ola marks it "Nie dla mnie". It isn't counted as taking part, and only she sees it, newest first in her hidden posts.
PUT {{api}}/posts/{{post}}/participation
X-User-Id: {{ola}}
X-User-Name: ola_demo
{ "type": "NOT_INTERESTED" }
HTTP *
[Asserts]
status < 300

GET {{api}}/posts/{{post}}
X-User-Id: {{ola}}
X-User-Name: ola_demo
HTTP 200
[Asserts]
jsonpath "$.myParticipation" == "NOT_INTERESTED"
jsonpath "$.participantCount" == 0

GET {{api}}/posts?participationTypes=NOT_INTERESTED&sortBy=STANCE_NEWEST&includePast=true&limit=1
X-User-Id: {{ola}}
X-User-Name: ola_demo
HTTP 200
[Asserts]
jsonpath "$.items[0].id" == "{{post}}"

# A like takes the mark back, and a mark the like.
PUT {{api}}/posts/{{post}}/reaction
X-User-Id: {{ola}}
X-User-Name: ola_demo
{ "type": "LIKE" }
HTTP *
[Asserts]
status < 300

GET {{api}}/posts/{{post}}
X-User-Id: {{ola}}
X-User-Name: ola_demo
HTTP 200
[Asserts]
jsonpath "$.myParticipation" not exists
jsonpath "$.positiveReactionCount" == 1

PUT {{api}}/posts/{{post}}/participation
X-User-Id: {{ola}}
X-User-Name: ola_demo
{ "type": "NOT_INTERESTED" }
HTTP *
[Asserts]
status < 300

GET {{api}}/posts/{{post}}
X-User-Id: {{ola}}
X-User-Name: ola_demo
HTTP 200
[Asserts]
jsonpath "$.myReaction" not exists
jsonpath "$.positiveReactionCount" == 0

DELETE {{api}}/posts/{{post}}/participation
X-User-Id: {{ola}}
X-User-Name: ola_demo
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

GET {{api}}/posts/{{post}}/friends
X-User-Id: {{jan}}
X-User-Name: jan_demo
HTTP 404

# One author can't have two posts from one event page, so a scraper run that retries a post gets a 409.
POST {{api}}/posts
X-User-Id: {{anna}}
X-User-Name: anna_demo
{ "title": "Z importu", "description": "Opis.", "eventUrl": "https://example.com/imp-{{newUuid}}", "startsAt": "2030-03-01T20:00:00Z", "type": "ONLINE" }
HTTP 201
[Captures]
imported: jsonpath "$.id"
page: jsonpath "$.eventUrl"

POST {{api}}/posts
X-User-Id: {{anna}}
X-User-Name: anna_demo
{ "title": "Z importu", "description": "Opis.", "eventUrl": "{{page}}", "startsAt": "2030-03-01T20:00:00Z", "type": "ONLINE" }
HTTP 409

DELETE {{api}}/posts/{{imported}}
X-User-Id: {{anna}}
X-User-Name: anna_demo
HTTP 204

# Popularne counts likes and stances, as the feed does: two stances outrank one like.
POST {{api}}/posts
X-User-Id: {{anna}}
X-User-Name: anna_demo
{ "title": "Seans pod gwiazdami, polubiony", "description": "Kino plenerowe.", "startsAt": "2030-04-01T20:00:00Z", "type": "ONLINE" }
HTTP 201
[Captures]
liked: jsonpath "$.id"

POST {{api}}/posts
X-User-Id: {{anna}}
X-User-Name: anna_demo
{ "title": "Seans pod gwiazdami, zaplanowany", "description": "Kino plenerowe.", "startsAt": "2030-04-02T20:00:00Z", "type": "ONLINE" }
HTTP 201
[Captures]
planned: jsonpath "$.id"

PUT {{api}}/posts/{{liked}}/reaction
X-User-Id: {{jan}}
X-User-Name: jan_demo
{ "type": "LIKE" }
HTTP *
[Asserts]
status < 300

PUT {{api}}/posts/{{planned}}/participation
X-User-Id: {{ola}}
X-User-Name: ola_demo
{ "type": "INTERESTED" }
HTTP *
[Asserts]
status < 300

PUT {{api}}/posts/{{planned}}/participation
X-User-Id: {{iza}}
X-User-Name: iza_demo
{ "type": "TAKES_PART" }
HTTP *
[Asserts]
status < 300

GET {{api}}/posts?sortBy=POPULAR&authorId={{anna}}&q=seans%20gwiazdami&limit=1
X-Anonymous: true
HTTP 200
[Captures]
popular_next: jsonpath "$.page.nextCursor"
[Asserts]
jsonpath "$.items[0].id" == "{{planned}}"

GET {{api}}/posts?sortBy=POPULAR&authorId={{anna}}&q=seans%20gwiazdami&limit=1&cursor={{popular_next}}
X-Anonymous: true
HTTP 200
[Asserts]
jsonpath "$.items[0].id" == "{{liked}}"

DELETE {{api}}/posts/{{liked}}
X-User-Id: {{anna}}
X-User-Name: anna_demo
HTTP *
[Asserts]
status < 300

DELETE {{api}}/posts/{{planned}}
X-User-Id: {{anna}}
X-User-Name: anna_demo
HTTP *
[Asserts]
status < 300

# The start can't move to the past, but an edit may send an unchanged start back, as the edit form always does. (Once
# an event has ended, Interesuje mnie and Biorę udział are refused too; the database tests cover it, since a post can't be
# created in the past.)
POST {{api}}/posts
X-User-Id: {{anna}}
X-User-Name: anna_demo
{ "title": "Koncert w Ergo", "description": "Wieczór z jazzem.", "startsAt": "2030-03-01T19:00:00Z", "type": "ONLINE" }
HTTP 201
[Captures]
dated: jsonpath "$.id"

PATCH {{api}}/posts/{{dated}}
X-User-Id: {{anna}}
X-User-Name: anna_demo
{ "title": "Koncert w Ergo Arenie", "startsAt": "2030-03-01T19:00:00Z" }
HTTP 200

PATCH {{api}}/posts/{{dated}}
X-User-Id: {{anna}}
X-User-Name: anna_demo
{ "startsAt": "2020-03-01T19:00:00Z" }
HTTP 400

DELETE {{api}}/posts/{{dated}}
X-User-Id: {{anna}}
X-User-Name: anna_demo
HTTP 204

# Kinds of event. The embedding worker derives them after a post is saved (locally: make embed-posts), so these use
# the seeded posts. A post matches any kind asked for; kids=true keeps only posts for children.
GET {{api}}/posts?kinds=concerts&limit=1
X-Anonymous: true
HTTP 200
[Captures]
concert: jsonpath "$.items[0].id"

GET {{api}}/posts/{{concert}}
X-Anonymous: true
HTTP 200
[Asserts]
jsonpath "$.kinds" contains "concerts"

GET {{api}}/posts?kinds=no_such_kind
X-Anonymous: true
HTTP 200
[Asserts]
jsonpath "$.items" count == 0

GET {{api}}/posts?kinds=Concerts
X-Anonymous: true
HTTP 400

GET {{api}}/posts/map?leftUpper.latitude=55&leftUpper.longitude=18&rightBottom.latitude=54&rightBottom.longitude=19.2&kinds=concerts,stage&includePast=true
X-Anonymous: true
HTTP 200
[Asserts]
jsonpath "$" count > 0
jsonpath "$[0].kind" exists

# A filtered pin names the kind it was found by, even where that kind is the post's second: every pin says concerts.
GET {{api}}/posts/map?leftUpper.latitude=55&leftUpper.longitude=18&rightBottom.latitude=54&rightBottom.longitude=19.2&kinds=concerts&includePast=true
X-Anonymous: true
HTTP 200
[Captures]
concert_pins: jsonpath "$" count
[Asserts]
jsonpath "$" count > 0
jsonpath "$[0].kinds" contains "concerts"

GET {{api}}/posts/map?leftUpper.latitude=55&leftUpper.longitude=18&rightBottom.latitude=54&rightBottom.longitude=19.2&kinds=concerts&includePast=true
X-Anonymous: true
HTTP 200
[Asserts]
jsonpath "$[?(@.kind == 'concerts')]" count == {{concert_pins}}
