FROM postgis/postgis:18-3.6-alpine

# pgvector is built without LLVM bitcode (Alpine has no clang/llvm 19) and with OPTFLAGS empty, so no -march=native
# and the image runs on any amd64 host.
RUN apk add --no-cache git build-base linux-headers \
    && git clone --branch v0.8.1 --depth 1 https://github.com/pgvector/pgvector.git /tmp/pgvector \
    && cd /tmp/pgvector \
    && make with_llvm=no OPTFLAGS="" \
    && make with_llvm=no install \
    && apk del git build-base linux-headers \
    && rm -rf /tmp/pgvector
