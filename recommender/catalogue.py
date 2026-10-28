import hashlib
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, Field, model_validator

KEY_PATTERN = r'^[a-z][a-z0-9_]*$'


class Interest(BaseModel, frozen=True):
    key: str = Field(pattern=KEY_PATTERN, max_length=64)
    label_pl: str = Field(min_length=1, max_length=128)
    label_en: str = Field(min_length=1, max_length=128)
    status: Literal['active', 'retired']
    text: str = Field(min_length=1)


class Catalogue(BaseModel, frozen=True):
    version: int = Field(gt=0)
    interests: tuple[Interest, ...] = Field(min_length=1)

    @model_validator(mode='after')
    def keys_are_unique(self):
        keys = [i.key for i in self.interests]
        repeated = sorted({k for k in keys if keys.count(k) > 1})
        if repeated:
            raise ValueError(f'catalogue {self.version} repeats keys {repeated}')
        return self


class CatalogueRejected(Exception):
    pass


def read_catalogue(path: Path) -> tuple[Catalogue, str]:
    raw = path.read_bytes()
    return Catalogue.model_validate_json(raw), hashlib.sha256(raw).hexdigest()


def latest_version(conn) -> int | None:
    return conn.execute('SELECT max(version) FROM interest_catalogue_versions').fetchone()[0]


def publish_catalogue(conn, catalogue: Catalogue, sha256: str) -> bool:
    conn.execute('SELECT pg_advisory_xact_lock(78372101)')
    row = conn.execute('SELECT sha256 FROM interest_catalogue_versions WHERE version=%s',
                       (catalogue.version,)).fetchone()
    if row:
        if row[0] != sha256:
            raise CatalogueRejected(f'publish catalogue {catalogue.version}: version already published with other content')
        return False
    latest = latest_version(conn)
    if latest is not None and catalogue.version < latest:
        raise CatalogueRejected(f'publish catalogue {catalogue.version}: older than published version {latest}')
    published = {r[0] for r in conn.execute('SELECT key FROM interests')}
    dropped = sorted(published - {i.key for i in catalogue.interests})
    if dropped:
        raise CatalogueRejected(f'publish catalogue {catalogue.version}: keys {dropped} missing; retire them instead')
    conn.execute('INSERT INTO interest_catalogue_versions(version, sha256) VALUES (%s, %s)',
                 (catalogue.version, sha256))
    for i in catalogue.interests:
        conn.execute(
            'INSERT INTO interests(key, label_pl, label_en, embedding_text, status, catalogue_version) '
            'VALUES (%s, %s, %s, %s, %s, %s) ON CONFLICT (key) DO UPDATE SET label_pl=excluded.label_pl, '
            'label_en=excluded.label_en, embedding_text=excluded.embedding_text, status=excluded.status, '
            'catalogue_version=excluded.catalogue_version',
            (i.key, i.label_pl, i.label_en, i.text, i.status, catalogue.version))
    return True


def list_active_interests(conn) -> tuple[int | None, list[dict]]:
    rows = conn.execute("SELECT key, label_pl, label_en FROM interests WHERE status='active' ORDER BY key").fetchall()
    return latest_version(conn), [{'key': k, 'labelPl': pl, 'labelEn': en} for k, pl, en in rows]
