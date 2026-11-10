# Who the local API thinks you are. On AWS a Cognito token decides, and these headers are ignored.

# No header at all is local_dev, an admin.
GET {{api}}/auth/me
HTTP 200
[Asserts]
jsonpath "$.username" == "local_dev"

# X-User-Id is that user, a regular one. GET /auth/me also creates a user it doesn't know yet.
GET {{api}}/auth/me
X-User-Id: {{anna}}
X-User-Name: anna_demo
HTTP 200
[Asserts]
jsonpath "$.username" == "anna_demo"
jsonpath "$.interestsSelected" == true

# X-Anonymous is a guest: no profile, but public lists still work.
GET {{api}}/auth/me
X-Anonymous: true
HTTP 401

GET {{api}}/feed?limit=1
X-Anonymous: true
HTTP 200
