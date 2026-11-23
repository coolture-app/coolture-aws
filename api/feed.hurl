# Reading posts: the ranked feed, the full list and the map. Rules and every parameter are in
# aws/lambdas/post-read/contracts/. Needs the seeded events (make seed).

# Anna's ranked feed. Later pages send only the cursor and the same filters.
GET {{api}}/feed
X-User-Id: {{anna}}
X-User-Name: anna_demo
[Query]
limit: 5
latitude: 54.35
longitude: 18.65
HTTP 200
[Captures]
cursor: jsonpath "$.page.nextCursor"
[Asserts]
jsonpath "$.mode" == "RANKED"
jsonpath "$.items" count == 5
jsonpath "$.page.hasMore" == true

GET {{api}}/feed
X-User-Id: {{anna}}
X-User-Name: anna_demo
[Query]
limit: 5
cursor: {{cursor}}
HTTP 200
[Asserts]
jsonpath "$.items" count == 5

# Jan sends no position, so proximity measures from his home in Gdynia (the seed's). The cursor is opaque to clients;
# it's read here only because nothing else shows where the session measures from.
GET {{api}}/feed
X-User-Id: {{jan}}
X-User-Name: jan_demo
[Query]
limit: 5
HTTP 200
[Asserts]
jsonpath "$.page.nextCursor" base64UrlSafeDecode charsetDecode "utf-8" contains "\"lat\":54.52"

# A guest gets a feed too, ranked without a taste.
GET {{api}}/feed?limit=5
X-Anonymous: true
HTTP 200

# Every visible post, with filters and a sort. Location and radius go together.
GET {{api}}/posts
[Query]
type: OFFLINE
sortBy: NEAREST
latitude: 54.35
longitude: 18.65
radiusKm: 10
limit: 5
HTTP 200
[Asserts]
jsonpath "$.items[0].location.coordinates.latitude" exists

GET {{api}}/posts?latitude=54.35&limit=5
HTTP 400

# Pins for the map, inside a box.
GET {{api}}/posts/map
[Query]
leftUpper.latitude: 54.6
leftUpper.longitude: 18.3
rightBottom.latitude: 54.3
rightBottom.longitude: 18.8
HTTP 200
[Asserts]
jsonpath "$[0].startsAt" exists
jsonpath "$[0].description" not exists

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
