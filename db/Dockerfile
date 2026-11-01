FROM postgis/postgis:18-3.6-alpine

# Alpine no longer ships the clang/llvm 19 packages: build pgvector without LLVM bitcode, and without -march=native (OPTFLAGS) so the image runs on any amd64 host.
RUN apk add --no-cache git build-base linux-headers \
    && git clone --branch v0.8.1 --depth 1 https://github.com/pgvector/pgvector.git /tmp/pgvector \
    && cd /tmp/pgvector \
    && make with_llvm=no OPTFLAGS="" \
    && make with_llvm=no install \
    && apk del git build-base linux-headers \
    && rm -rf /tmp/pgvector
