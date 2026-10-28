import math
from dataclasses import dataclass
from uuid import UUID

import psycopg
from pydantic import BaseModel, Field

from catalogue import latest_version
from embedding import parse_vector, text_sha256, vector_literal

MIN_INTERESTS = 3
MAX_INTERESTS = 10
DEFAULT_WEIGHT = 1.0
MAX_WEIGHT = 1.0


class InterestChoice(BaseModel, frozen=True):
    key: str = Field(min_length=1, max_length=64)
    weight: float = Field(default=DEFAULT_WEIGHT, gt=0, le=MAX_WEIGHT)


class SelectionRequest(BaseModel, frozen=True):
    interests: tuple[InterestChoice, ...]


@dataclass(frozen=True)
class SelectionProblems:
    count: int
    unknown: tuple[str, ...]
    duplicated: tuple[str, ...]
    retired: tuple[str, ...]

    def is_valid(self) -> bool:
        return (MIN_INTERESTS <= self.count <= MAX_INTERESTS
                and not (self.unknown or self.duplicated or self.retired))


@dataclass(frozen=True)
class ProfileRevision:
    user_id: UUID
    revision: int
    changed: bool
    choices: tuple[InterestChoice, ...]


class UserUnknown(Exception):
    pass


class ProfileChanged(Exception):
    pass


class SelectionRejected(Exception):
    def __init__(self, problems: SelectionProblems):
        super().__init__('replace selection: selection rejected')
        self.problems = problems


class EmbeddingsNotReady(Exception):
    def __init__(self, model_key: str, keys: list[str]):
        super().__init__(f'replace selection: no current {model_key} vectors for {keys}')
        self.model_key = model_key
        self.keys = keys


def find_problems(choices: tuple[InterestChoice, ...], statuses: dict[str, str]) -> SelectionProblems:
    keys = [c.key for c in choices]
    distinct = list(dict.fromkeys(keys))
    return SelectionProblems(
        count=len(distinct),
        unknown=tuple(k for k in distinct if k not in statuses),
        duplicated=tuple(k for k in distinct if keys.count(k) > 1),
        retired=tuple(k for k in distinct if statuses.get(k) == 'retired'))


def user_vector(weighted: list[tuple[float, list[float]]]) -> list[float]:
    total = sum(w for w, _ in weighted)
    mean = [sum(w * v[i] for w, v in weighted) / total for i in range(len(weighted[0][1]))]
    norm = math.sqrt(sum(x * x for x in mean))
    return [x / norm for x in mean]


def replace_selection(conn, user_id: UUID, choices: tuple[InterestChoice, ...], model_key: str) -> ProfileRevision:
    if not conn.execute('SELECT 1 FROM app_views.users WHERE id=%s', (user_id,)).fetchone():
        raise UserUnknown(f'replace selection: user {user_id} unknown')
    statuses = dict(conn.execute('SELECT key, status FROM interests').fetchall())
    problems = find_problems(choices, statuses)
    if not problems.is_valid():
        raise SelectionRejected(problems)
    wanted = {c.key: c.weight for c in choices}
    latest = conn.execute('SELECT max(revision) FROM user_interest_profiles WHERE user_id=%s', (user_id,)).fetchone()[0]
    if latest is not None:
        current = dict(conn.execute(
            'SELECT interest_key, weight FROM user_interest_selections WHERE user_id=%s AND revision=%s',
            (user_id, latest)).fetchall())
        if current.keys() == wanted.keys() and all(math.isclose(current[k], w, rel_tol=1e-6) for k, w in wanted.items()):
            return ProfileRevision(user_id, latest, False, sorted_choices(current))
    vector = interest_user_vector(conn, wanted, model_key)
    revision = (latest or 0) + 1
    try:
        conn.execute(
            'INSERT INTO user_interest_profiles(user_id, revision, catalogue_version, model_key) VALUES (%s, %s, %s, %s)',
            (user_id, revision, latest_version(conn), model_key))
    except psycopg.errors.UniqueViolation as conflict:
        raise ProfileChanged(f'replace selection: user {user_id} revision {revision} already exists') from conflict
    for key, weight in wanted.items():
        conn.execute(
            'INSERT INTO user_interest_selections(user_id, revision, interest_key, weight) VALUES (%s, %s, %s, %s)',
            (user_id, revision, key, weight))
    conn.execute(
        'INSERT INTO user_embeddings(user_id, embedding) VALUES (%s, %s::vector) ON CONFLICT (user_id) '
        'DO UPDATE SET embedding=excluded.embedding, updated_at=now()', (user_id, vector_literal(vector)))
    return ProfileRevision(user_id, revision, True, sorted_choices(wanted))


def sorted_choices(weights: dict[str, float]) -> tuple[InterestChoice, ...]:
    return tuple(InterestChoice(key=k, weight=weights[k]) for k in sorted(weights))


def interest_user_vector(conn, wanted: dict[str, float], model_key: str) -> list[float]:
    rows = conn.execute(
        'SELECT i.key, i.embedding_text, e.text_sha256, e.embedding::text FROM interests i '
        'JOIN interest_embeddings e ON e.interest_key=i.key AND e.model_key=%s WHERE i.key = ANY(%s)',
        (model_key, list(wanted))).fetchall()
    current = {key: parse_vector(vector) for key, text, sha, vector in rows if sha == text_sha256(text)}
    missing = sorted(set(wanted) - set(current))
    if missing:
        raise EmbeddingsNotReady(model_key, missing)
    return user_vector([(wanted[k], current[k]) for k in sorted(wanted)])
