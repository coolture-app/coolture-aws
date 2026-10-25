#!/bin/sh

set -eu

G="chroot /proc/1/root /garage"

for i in $(seq 1 20); do
  if $G node id -q 2>/dev/null; then break; fi
  echo "Waiting for Garage RPC... ($i/20)"
  sleep 3
done
$G node id -q || { echo "Garage RPC not ready"; exit 1; }

if $G status 2>&1 | grep -q 'NO ROLE ASSIGNED'; then
  NODE_ID=$($G node id -q | cut -c1-16)
  $G layout assign "$NODE_ID" -z local -c 1G
  $G layout apply --version 1
fi

# KEY IMPORT
# `key info <ID>` returns non-zero value if key doesn't exist
# Key is referenced by ID, not by name, bcs multiple keys can share the same name
if $G key info "$GARAGE_ACCESS_KEY_ID" >/dev/null 2>&1; then
  echo "Key: $GARAGE_ACCESS_KEY_ID already present, skipping key import"
else
  echo "Importing key: $GARAGE_ACCESS_KEY_ID..."
  $G key import --yes \
    -n "$GARAGE_KEY_NAME" \
    "$GARAGE_ACCESS_KEY_ID" \
    "$GARAGE_ACCESS_KEY_SECRET"
fi

# BUCKET CREATION
if $G bucket info "$GARAGE_BUCKET_NAME" >/dev/null 2>&1; then
  echo "Bucket $GARAGE_BUCKET_NAME already exists"
else
  echo "Creating bucket: $GARAGE_BUCKET_NAME..."
  $G bucket create "$GARAGE_BUCKET_NAME"
fi

# GRANT BUCKET ACCESS TO THE KEY
# Allowing the same key multiple times is idempotent
echo "Allowing key: $GARAGE_ACCESS_KEY_ID to bucket: $GARAGE_BUCKET_NAME..."
$G bucket allow "$GARAGE_BUCKET_NAME" \
  --key "$GARAGE_ACCESS_KEY_ID" \
  --read --write --owner

# SAME NAME KEY DELETION
# Removing a key auto-revokes its bucket grants
# `key list` output looks like this:
# ID    Created Name Expiration
# GK... date    name ...
echo "Trying to delete old keys..."
$G key list 2>/dev/null | while read -r id _date name _rest; do
  # Skip header "ID ..." and blank lines
  # Keys are starting with "GK"
  case "$id" in GK*) ;; *) continue ;; esac
  if [ "$name" = "$GARAGE_KEY_NAME" ] && [ "$id" != "$GARAGE_ACCESS_KEY_ID" ]; then
    echo "Removing old key: $id"
    $G key delete --yes "$id" || echo "WARN: failed to delete key: $id"
  fi
done

echo "Garage initialized."

# Configure CORS so browsers can PUT/GET directly
apk add --no-cache aws-cli --quiet

aws s3api put-bucket-cors \
  --endpoint-url "http://127.0.0.1:3900" \
  --bucket "$GARAGE_BUCKET_NAME" \
  --cors-configuration '{
    "CORSRules": [{
      "AllowedOrigins": ["*"],
      "AllowedMethods": ["GET", "PUT", "HEAD", "DELETE"],
      "AllowedHeaders": ["*"],
      "ExposeHeaders": ["ETag", "x-amz-checksum-crc32"],
      "MaxAgeSeconds": 3600
    }]
  }'

echo "CORS configured."