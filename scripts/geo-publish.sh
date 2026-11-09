#!/usr/bin/env bash
# make geo-publish: puts the local geocoder (make geo-index) on the AWS instance. Uploads the image's Photon jar, photon.sh
# and the index to the geocoder stack's bucket, then reboots the instance, which fetches them as it starts.
set -euo pipefail

ENVIRONMENT="${1:-dev}"
cd "$(dirname "$0")/.."
export AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-eu-north-1}"
export MSYS_NO_PATHCONV=1
stack="coolture-geocoder-$ENVIRONMENT"

output() {
    aws cloudformation describe-stacks --stack-name "$stack" --query "Stacks[0].Outputs[?OutputKey=='$1'].OutputValue" --output text
}

if ! docker volume inspect coolture-geocoder-index > /dev/null 2>&1; then
    echo "No local index. Run make geo-index first." >&2
    exit 1
fi
bucket="$(output IndexBucketName)"
instance="$(output InstanceId)"

out=.aws-sam/geocoder
rm -rf "$out"
mkdir -p "$out"
# Docker Desktop on Windows needs D:/... for a bind mount, not Git Bash's /d/...
host_out="$(cd "$out" && (pwd -W 2> /dev/null || pwd))"

echo "==> packing Photon and the index"
# A serving Photon keeps writing to its index, which breaks tar and could copy a torn index. It starts again afterwards.
if docker compose --profile geo ps --status running --services | grep -qx photon; then
    docker compose --profile geo stop photon
    trap 'docker compose --profile geo start photon' EXIT
fi
docker compose --profile geo run --rm --no-deps -v "$host_out:/out" --entrypoint sh photon \
    -c 'cp /photon/photon.jar /out/ && tar czf /out/index.tar.gz -C /data photon_data'
ls -lh "$out"

echo "==> uploading to s3://$bucket/photon/"
aws s3 cp "$out/photon.jar" "s3://$bucket/photon/photon.jar"
aws s3 cp geocoder/photon.sh "s3://$bucket/photon/photon.sh"
aws s3 cp "$out/index.tar.gz" "s3://$bucket/photon/index.tar.gz"
rm -rf "$out"

echo "==> rebooting $instance"
aws ec2 reboot-instances --instance-ids "$instance"
echo "The geocoder answers with the new index in a few minutes, at /geo on the site."
