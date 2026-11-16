"""Personalised PageRank over users, events, venues and interests. No I/O."""
from collections.abc import Iterator
from dataclasses import dataclass, field

import numpy as np
from scipy import sparse
from scipy.stats import rankdata

INTERESTS_PER_EVENT = 2
EVENT_NEIGHBOURS = 5
RESTART = 0.3
ITERATIONS = 60
BATCH = 256
CHUNK = 1024


@dataclass(frozen=True)
class Inputs:
    """Indices are positions in the lists. Vectors are one model's, unit length. Only scorable events get scores."""

    event_ids: list
    event_vectors: np.ndarray
    scorable: np.ndarray
    venues: list
    post_average: np.ndarray
    interest_vectors: np.ndarray
    user_ids: list
    picks: list = field(default_factory=list)
    stances: list = field(default_factory=list)
    follows: list = field(default_factory=list)
    hidden: dict = field(default_factory=dict)


def centred(vectors: np.ndarray, average: np.ndarray) -> np.ndarray:
    out = vectors - average
    lengths = np.linalg.norm(out, axis=1, keepdims=True)
    lengths[lengths == 0] = 1
    return out / lengths


def content_edges(inputs: Inputs) -> tuple[list, list]:
    """By rank, not by a cosine threshold: the model's cosines sit in a narrow band. Event–event edges join scorable
    events only, as measured; ended ones keep their other edges. An event equal to the average post has no content."""
    events = centred(inputs.event_vectors, inputs.post_average)
    interests = centred(inputs.interest_vectors, inputs.interest_vectors.mean(axis=0))
    has_content = events.any(axis=1)
    nearest = np.argsort(-(events @ interests.T), axis=1)[:, :INTERESTS_PER_EVENT]
    to_interest = [(int(e), int(i)) for e in np.flatnonzero(has_content) for i in nearest[e]]

    live = np.flatnonzero(inputs.scorable & has_content)
    k = min(EVENT_NEIGHBOURS, len(live) - 1)
    to_event = set()
    # In blocks of rows, so memory doesn't grow with the square of the events.
    for start in range(0, len(live) if k > 0 else 0, CHUNK):
        rows = np.arange(start, min(start + CHUNK, len(live)))
        pairs = events[live[rows]] @ events[live].T
        pairs[np.arange(len(rows)), rows] = -np.inf
        for row, nearest_events in zip(rows, np.argpartition(pairs, -k, axis=1)[:, -k:]):
            to_event.update(tuple(sorted((int(live[row]), int(live[b])))) for b in nearest_events)
    return to_interest, sorted(to_event)


def adjacency(inputs: Inputs) -> sparse.csr_matrix:
    n_events, n_interests = len(inputs.event_ids), len(inputs.interest_vectors)
    venue_index = {v: j for j, v in enumerate(sorted({v for v in inputs.venues if v}))}
    interest_at, venue_at = n_events, n_events + n_interests
    user_at = venue_at + len(venue_index)
    size = user_at + len(inputs.user_ids)
    rows, cols, vals = [], [], []

    def edge(a, b, weight=1.0, both=True):
        rows.append(a)
        cols.append(b)
        vals.append(weight)
        if both:
            edge(b, a, weight, both=False)

    for e, venue in enumerate(inputs.venues):
        if venue:
            edge(e, venue_at + venue_index[venue])
    to_interest, to_event = content_edges(inputs)
    for e, i in to_interest:
        edge(e, interest_at + i)
    for e, f in to_event:
        edge(e, f)
    for u, i in inputs.picks:
        edge(user_at + u, interest_at + i)
    for u, e, weight in inputs.stances:
        edge(user_at + u, e, weight)
    for follower, followed in inputs.follows:
        edge(user_at + follower, user_at + followed, both=False)
    return sparse.csr_matrix((vals, (rows, cols)), shape=(size, size))


def scores(inputs: Inputs) -> Iterator[tuple[int, int, float]]:
    """(user, event, score): the walk's mass ranked among the user's candidates, 0 to 1. A user whose walk can't tell
    them apart gets no rows, which the feed reads as 0.5 for all."""
    if not inputs.event_ids or not inputs.user_ids:
        return
    a = adjacency(inputs)
    user_at = a.shape[0] - len(inputs.user_ids)
    degree = np.asarray(a.sum(axis=1)).ravel()
    walkers = [u for u in range(len(inputs.user_ids)) if degree[user_at + u] > 0]
    degree[degree == 0] = 1
    step = (sparse.diags(1 / degree) @ a).T.tocsr()

    for start in range(0, len(walkers), BATCH):
        batch = walkers[start:start + BATCH]
        seed = np.zeros((a.shape[0], len(batch)))
        seed[[user_at + u for u in batch], range(len(batch))] = 1
        mass = seed.copy()
        for _ in range(ITERATIONS):
            mass = RESTART * seed + (1 - RESTART) * (step @ mass)
        for column, u in enumerate(batch):
            allowed = inputs.scorable.copy()
            allowed[list(inputs.hidden.get(u, ()))] = False
            candidates = np.flatnonzero(allowed)
            values = mass[candidates, column]
            if len(candidates) < 2 or np.ptp(values) == 0:
                continue
            ranked = (rankdata(values) - 1) / (len(candidates) - 1)
            yield from ((u, int(e), float(s)) for e, s in zip(candidates, ranked))
