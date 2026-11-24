# Uploading a photo: the API hands out a presigned URL, the file goes straight to storage (Garage locally, S3 on AWS),
# and the API is told when it's done. A post takes EVENT_MEDIA ids in mediaIds; an avatar takes a PROFILE_IMAGE and a
# PROFILE_IMAGE_THUMBNAIL.

# 1. Ask for an upload. The size and type must match the file sent next.
POST {{api}}/media/uploads/init
X-User-Id: {{anna}}
X-User-Name: anna_demo
{ "purpose": "PROFILE_IMAGE", "mimeType": "image/png", "sizeBytes": 69, "fileName": "pixel.png", "width": 1, "height": 1 }
HTTP 201
[Captures]
full: jsonpath "$.mediaId"
full_url: jsonpath "$.uploadUrl"
[Asserts]
jsonpath "$.requiredHeaders['content-type']" == "image/png"

# 2. Send the file to storage, with the headers the API asked for. No API headers here.
PUT {{full_url}}
Content-Type: image/png
file,fixtures/pixel.png;
HTTP 200

# 3. Tell the API it's there.
POST {{api}}/media/uploads/{{full}}/complete
X-User-Id: {{anna}}
X-User-Name: anna_demo
HTTP 200
[Asserts]
jsonpath "$.status" == "UPLOADED"

# The same for the thumbnail.
POST {{api}}/media/uploads/init
X-User-Id: {{anna}}
X-User-Name: anna_demo
{ "purpose": "PROFILE_IMAGE_THUMBNAIL", "mimeType": "image/png", "sizeBytes": 69, "fileName": "pixel.png", "width": 1, "height": 1 }
HTTP 201
[Captures]
thumb: jsonpath "$.mediaId"
thumb_url: jsonpath "$.uploadUrl"

PUT {{thumb_url}}
Content-Type: image/png
file,fixtures/pixel.png;
HTTP 200

POST {{api}}/media/uploads/{{thumb}}/complete
X-User-Id: {{anna}}
X-User-Name: anna_demo
HTTP 200

# Make them Anna's avatar. Its URL serves the file.
PUT {{api}}/users/{{anna}}/profile-image
X-User-Id: {{anna}}
X-User-Name: anna_demo
{ "fullMediaId": "{{full}}", "thumbnailMediaId": "{{thumb}}" }
HTTP 200

GET {{api}}/media/{{full}}
HTTP 200
[Captures]
full_download: jsonpath "$.url"

GET {{full_download}}
HTTP 200
[Asserts]
bytes count == 69

# Jan can't delete Anna's files. Removing the avatar puts Anna back as she was.
DELETE {{api}}/media/{{full}}
X-User-Id: {{jan}}
X-User-Name: jan_demo
HTTP 403

DELETE {{api}}/users/{{anna}}/profile-image
X-User-Id: {{anna}}
X-User-Name: anna_demo
HTTP *
[Asserts]
status < 300
