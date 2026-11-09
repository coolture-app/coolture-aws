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
