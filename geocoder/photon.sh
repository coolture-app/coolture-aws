#!/bin/sh
# Runs Photon the same way in the local container and on the AWS instance, which gets this file from make geo-publish.
#   import: builds the Poland index from GraphHopper's weekly dump, with 3 GB of heap.
#   serve:  answers on port 2322, in the 1 GB of a t4g.micro.
set -eu

DATA="${DATA:-/data}"
PHOTON_JAR="${PHOTON_JAR:-/photon/photon.jar}"
DUMP_URL="${DUMP_URL:-https://download1.graphhopper.com/public/europe/poland/photon-dump-poland-1.0-latest.jsonl.zst}"

case "${1:-serve}" in
    import)
        # Built beside the old index and swapped in at the end, so a failed import leaves the old one serving.
        rm -rf "$DATA/import"
        mkdir -p "$DATA/import"
        curl -fsSL "$DUMP_URL" | zstd -dc |
            java -Xmx3g -jar "$PHOTON_JAR" import -import-file - -languages pl,en -data-dir "$DATA/import"
        rm -rf "$DATA/photon_data"
        mv "$DATA/import/photon_data" "$DATA/photon_data"
        rm -rf "$DATA/import"
        echo "Index ready in $DATA/photon_data"
        ;;
    serve)
        if [ ! -d "$DATA/photon_data" ]; then
            echo "No index in $DATA/photon_data. Locally: make geo-index. On AWS: make geo-publish." >&2
            exit 1
        fi
        exec java -Xmx512m -jar "$PHOTON_JAR" serve -listen-ip 0.0.0.0 -listen-port 2322 -data-dir "$DATA"
        ;;
    *)
        echo "Usage: $0 import|serve" >&2
        exit 2
        ;;
esac
