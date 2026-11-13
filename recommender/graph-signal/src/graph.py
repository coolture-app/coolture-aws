"""Personalised PageRank over users, events, venues and interests. No I/O."""
from dataclasses import dataclass, field

import numpy as np
from scipy import sparse
from scipy.stats import rankdata

INTERESTS_PER_EVENT = 2
EVENT_NEIGHBOURS = 5
RESTART = 0.3
ITERATIONS = 60
BATCH = 256


@dataclass(frozen=True)
class Inputs:
    """Indices are positions in the lists. Vectors are one model's, unit length."""

    event_ids: list
    event_vectors: np.ndarray
    scorable: np.ndarray  # per event: hasn't ended, so it may get a score
    venues: list  # per event: a venue's key, or None
    post_average: np.ndarray  # the average post, as the feed takes it out
    interest_vectors: np.ndarray
    user_ids: list
    picks: list = field(default_factory=list)  # (user, interest)
    stances: list = field(default_factory=list)  # (user, event, weight), positive and faded
    follows: list = field(default_factory=list)  # (follower, followed)
    hidden: dict = field(default_factory=dict)  # user: events the feed never shows them as new


def centred(vectors: np.ndarray, average: np.ndarray) -> np.ndarray:
    out = vectors - average
    lengths = np.linalg.norm(out, axis=1, keepdims=True)
    lengths[lengths == 0] = 1
    return out / lengths


def content_edges(inputs: Inputs) -> tuple[list, list]:
    """By rank, not by a cosine threshold: the model's cosines sit in a narrow band."""
    events = centred(inputs.event_vectors, inputs.post_average)
    interests = centred(inputs.interest_vectors, inputs.interest_vectors.mean(axis=0))
    to_interest = [(e, int(i)) for e in range(len(events))
                   for i in np.argsort(-(interests @ events[e]))[:INTERESTS_PER_EVENT]]
    pairs = events @ events.T
    np.fill_diagonal(pairs, -np.inf)
    k = min(EVENT_NEIGHBOURS, len(events) - 1)
    to_event = sorted({(min(e, int(f)), max(e, int(f))) for e in range(len(events)) for f in np.argsort(-pairs[e])[:k]})
    return to_interest, to_event


def adjacency(inputs: Inputs) -> sparse.csr_matrix:
    """Nodes: events, interests, venues, users. Every edge goes both ways except a follow."""
    n_events, n_interests = len(inputs.event_ids), len(inputs.interest_vectors)
    venue_keys = sorted({v for v in inputs.venues if v})
    venue_index = {v: j for j, v in enumerate(venue_keys)}
    interest_at, venue_at = n_events, n_events + n_interests
    user_at = venue_at + len(venue_keys)
    size = user_at + len(inputs.user_ids)
    rows, cols, vals = [], [], []

    def both(a, b, weight=1.0):
        rows.extend((a, b))
        cols.extend((b, a))
        vals.extend((weight, weight))

    for e, venue in enumerate(inputs.venues):
        if venue:
            both(e, venue_at + venue_index[venue])
    to_interest, to_event = content_edges(inputs) if n_events > 1 else ([], [])
    for e, i in to_interest:
        both(e, interest_at + i)
    for e, f in to_event:
        both(e, f)
    for u, i in inputs.picks:
        both(user_at + u, interest_at + i)
    for u, e, weight in inputs.stances:
        both(user_at + u, e, weight)
    for follower, followed in inputs.follows:
        rows.append(user_at + follower)
        cols.append(user_at + followed)
        vals.append(1.0)
    return sparse.csr_matrix((vals, (rows, cols)), shape=(size, size))


def scores(inputs: Inputs) -> list[tuple[int, int, float]]:
    """(user, event, score): the walk's mass ranked among the user's candidates, 0 to 1. A user whose walk can't tell
    them apart gets no rows, which the feed reads as 0.5 for all."""
    n_events = len(inputs.event_ids)
    if n_events == 0 or not inputs.user_ids:
        return []
    a = adjacency(inputs)
    user_at = a.shape[0] - len(inputs.user_ids)
    degree = np.asarray(a.sum(axis=1)).ravel()
    walkers = [u for u in range(len(inputs.user_ids)) if degree[user_at + u] > 0]
    degree[degree == 0] = 1
    step = (sparse.diags(1 / degree) @ a).T.tocsr()

    out = []
    for start in range(0, len(walkers), BATCH):
        batch = walkers[start:start + BATCH]
        seed = np.zeros((a.shape[0], len(batch)))
        seed[[user_at + u for u in batch], range(len(batch))] = 1
        mass = seed.copy()
        for _ in range(ITERATIONS):
            mass = RESTART * seed + (1 - RESTART) * (step @ mass)
        for column, u in enumerate(batch):
            hidden = inputs.hidden.get(u, set())
            candidates = [e for e in range(n_events) if inputs.scorable[e] and e not in hidden]
            values = mass[candidates, column]
            if len(candidates) < 2 or np.ptp(values) == 0:
                continue
            ranked = (rankdata(values) - 1) / (len(candidates) - 1)
            out.extend((u, e, float(s)) for e, s in zip(candidates, ranked))
    return out
