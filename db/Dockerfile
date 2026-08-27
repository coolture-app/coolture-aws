FROM postgis/postgis:18-3.6-alpine

RUN apk add --no-cache git build-base linux-headers clang19 llvm19 \
    && git clone --branch v0.8.1 --depth 1 https://github.com/pgvector/pgvector.git /tmp/pgvector \
    && cd /tmp/pgvector \
    && make \
    && make install \
    && apk del git build-base linux-headers clang19 \
    && rm -rf /tmp/pgvector
