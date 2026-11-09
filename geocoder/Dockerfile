# Photon, the geocoder behind the post form's place search. make geo-publish copies this jar to the AWS instance,
# so both run the same version.
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl zstd && rm -rf /var/lib/apt/lists/*
ADD --checksum=sha256:a89707c0045e4807b2a1180e132e68e108d998709f48b6c94b98a6e281f571a5 \
    https://github.com/komoot/photon/releases/download/1.3.0/photon-1.3.0.jar /photon/photon.jar
COPY photon.sh /photon/photon.sh
EXPOSE 2322
ENTRYPOINT ["/bin/sh", "/photon/photon.sh"]
CMD ["serve"]
