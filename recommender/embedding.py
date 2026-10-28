import hashlib
from dataclasses import dataclass
from typing import Protocol

STORAGE_DIMENSION = 768
ENCODE_BATCH_SIZE = 8
RETRIEVAL_INSTRUCTION = 'Instruct: Given a web search query, retrieve relevant passages that answer the query'


class Embedder(Protocol):
    model_key: str
    dimension: int

    def embed_documents(self, texts: list[str]) -> list[list[float]]: ...

    def embed_queries(self, texts: list[str]) -> list[list[float]]: ...


@dataclass(frozen=True)
class ModelSpec:
    name: str
    hf_id: str
    revision: str
    native_dimension: int
    query_prefix: str = ''
    document_prefix: str = ''
    matryoshka_dimensions: tuple[int, ...] = ()
    trust_remote_code: bool = False

    def model_key(self, dimension: int) -> str:
        return f'{self.hf_id}@{self.revision[:12]}/{dimension}'


MODELS = {spec.name: spec for spec in (
    ModelSpec('stella-pl', 'sdadas/stella-pl-retrieval-8k', 'b9330a4545413ac3769709de419ad02907550c3d', 1024,
              query_prefix=f'{RETRIEVAL_INSTRUCTION}.\nQuery: ', trust_remote_code=True),
    ModelSpec('mmlw-roberta-large', 'sdadas/mmlw-retrieval-roberta-large-v2',
              '13701958e518c47b1f3f91b4d2cdd5afb3eb9331', 1024, query_prefix='[query]: '),
    ModelSpec('qwen3-0.6b', 'Qwen/Qwen3-Embedding-0.6B', '97b0c614be4d77ee51c0cef4e5f07c00f9eb65b3', 1024,
              query_prefix=f'{RETRIEVAL_INSTRUCTION}\nQuery:', matryoshka_dimensions=tuple(range(32, 1024))),
    ModelSpec('minilm-l6', 'sentence-transformers/all-MiniLM-L6-v2', '1110a243fdf4706b3f48f1d95db1a4f5529b4d41', 384),
    ModelSpec('mpnet-base', 'sentence-transformers/all-mpnet-base-v2', 'e8c3b32edf5434bc2275fc9bab85f82640a19130', 768),
    ModelSpec('e5-large', 'intfloat/e5-large-v2', 'f169b11e22de13617baa190a028a32f3493550b6', 1024,
              query_prefix='query: ', document_prefix='passage: '),
    ModelSpec('bge-large-en', 'BAAI/bge-large-en-v1.5', 'd4aa6901d3a41ba39fb536a557fa166f842b0e09', 1024,
              query_prefix='Represent this sentence for searching relevant passages: '),
)}


class DimensionUnsupported(Exception):
    pass


class SentenceTransformerEmbedder:
    def __init__(self, spec: ModelSpec, dimension: int, device: str = 'cpu'):
        if dimension != spec.native_dimension and dimension not in spec.matryoshka_dimensions:
            raise DimensionUnsupported(
                f'load {spec.name}: dimension {dimension} unsupported; native {spec.native_dimension}, '
                f'matryoshka {list(spec.matryoshka_dimensions)}')
        import torch
        from sentence_transformers import SentenceTransformer
        self.spec = spec
        self.dimension = dimension
        self.model_key = spec.model_key(dimension)
        self.model = SentenceTransformer(
            spec.hf_id, revision=spec.revision, device=device, trust_remote_code=spec.trust_remote_code,
            truncate_dim=None if dimension == spec.native_dimension else dimension,
            model_kwargs={'dtype': torch.float32})

    def embed_documents(self, texts: list[str]) -> list[list[float]]:
        return self._encode(self.spec.document_prefix, texts)

    def embed_queries(self, texts: list[str]) -> list[list[float]]:
        return self._encode(self.spec.query_prefix, texts)

    def _encode(self, prefix: str, texts: list[str]) -> list[list[float]]:
        return self.model.encode([prefix + t for t in texts], batch_size=ENCODE_BATCH_SIZE,
                                 normalize_embeddings=True, convert_to_numpy=True).tolist()


def text_sha256(text: str) -> str:
    return hashlib.sha256(text.encode()).hexdigest()


def vector_literal(vector) -> str:
    return '[' + ','.join(repr(float(x)) for x in vector) + ']'


def parse_vector(literal: str) -> list[float]:
    return [float(x) for x in literal.strip('[]').split(',')]


def embed_interests(conn, embedder: Embedder) -> int:
    if embedder.dimension != STORAGE_DIMENSION:
        raise DimensionUnsupported(
            f'embed interests with {embedder.model_key}: dimension {embedder.dimension}, storage holds {STORAGE_DIMENSION}')
    rows = conn.execute(
        'SELECT i.key, i.embedding_text, e.text_sha256 FROM interests i LEFT JOIN interest_embeddings e '
        'ON e.interest_key=i.key AND e.model_key=%s ORDER BY i.key', (embedder.model_key,)).fetchall()
    stale = [(key, text) for key, text, sha in rows if sha != text_sha256(text)]
    if not stale:
        return 0
    vectors = embedder.embed_queries([text for _, text in stale])
    for (key, text), vector in zip(stale, vectors, strict=True):
        conn.execute(
            'INSERT INTO interest_embeddings(interest_key, model_key, text_sha256, embedding) '
            'VALUES (%s, %s, %s, %s::vector) ON CONFLICT (interest_key, model_key) DO UPDATE '
            'SET text_sha256=excluded.text_sha256, embedding=excluded.embedding, created_at=now()',
            (key, embedder.model_key, text_sha256(text), vector_literal(vector)))
    return len(stale)
