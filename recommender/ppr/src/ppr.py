"""Personalised PageRank over users, events, venues and interests. No I/O."""
from collections.abc import Iterator
from dataclasses import dataclass
from uuid import UUID

import numpy as np
from scipy import sparse
from scipy.stats import rankdata

INTERESTS_PER_EVENT = 2
EVENT_NEIGHBOURS = 5
RESTART_PROBABILITY = 0.3
# Enough that (1 - RESTART_PROBABILITY) ** ITERATIONS, what the walk would still move, can't swap two ranks.
ITERATIONS = 60
USERS_PER_BATCH = 256
EVENTS_PER_BLOCK = 1024

Pairs = list[tuple[int, int]]


@dataclass(frozen=True)
class Inputs:
    """Users, events and interests are numbered by their position in user_ids, event_ids and interest_vectors.
    Vectors are one model's, unit length. Only events that haven't ended get scores."""

    event_ids: list[UUID]
    event_vectors: np.ndarray
    not_ended: np.ndarray
    venues: list[str | None]
    post_average: np.ndarray
    interest_vectors: np.ndarray
    user_ids: list[UUID]
    picks: Pairs  # (user, interest)
    stances: list[tuple[int, int, float]]  # (user, event, weight)
    friends: Pairs  # (user, friend), one row per direction
    hidden: dict[int, set[int]]  # user: events the feed never shows them as new


def centred_and_normalised(vectors: np.ndarray, average: np.ndarray) -> np.ndarray:
    out = vectors - average
    lengths = np.linalg.norm(out, axis=1, keepdims=True)
    lengths[lengths == 0] = 1
    return out / lengths


def content_edges(inputs: Inputs) -> tuple[Pairs, Pairs]:
    """(event, interest) and (event, event) pairs, from the vectors. An event equal to the average post has none."""
    events = centred_and_normalised(inputs.event_vectors, inputs.post_average)
    interests = centred_and_normalised(inputs.interest_vectors, inputs.interest_vectors.mean(axis=0))
    has_content = events.any(axis=1)
    return (nearest_interests(events, interests, np.flatnonzero(has_content)),
            nearest_events(events, np.flatnonzero(inputs.not_ended & has_content)))


def nearest_interests(events: np.ndarray, interests: np.ndarray, linked: np.ndarray) -> Pairs:
    """By rank, not by a cosine threshold: the model's cosines sit in a narrow band."""
    nearest = np.argsort(-(events @ interests.T), axis=1)[:, :INTERESTS_PER_EVENT]
    return [(int(e), int(i)) for e in linked for i in nearest[e]]


def nearest_events(events: np.ndarray, linked: np.ndarray) -> Pairs:
    """Each pair once, among the linked events."""
    k = min(EVENT_NEIGHBOURS, len(linked) - 1)
    if k < 1:
        return []
    pairs = set()
    # In blocks of rows, so memory doesn't grow with the square of the events.
    for start in range(0, len(linked), EVENTS_PER_BLOCK):
        block = np.arange(start, min(start + EVENTS_PER_BLOCK, len(linked)))
        similarity = events[linked[block]] @ events[linked].T
        similarity[np.arange(len(block)), block] = -np.inf
        for row, neighbours in zip(block, np.argpartition(similarity, -k, axis=1)[:, -k:]):
            pairs.update(tuple(sorted((int(linked[row]), int(linked[n])))) for n in neighbours)
    return sorted(pairs)


def adjacency(inputs: Inputs) -> sparse.csr_matrix:
    """Nodes are the events, then the interests, the venues, the users, and last each user's stances as their friends
    see them."""
    venue_names = sorted({v for v in inputs.venues if v})
    venue_index = {name: j for j, name in enumerate(venue_names)}
    first_interest = len(inputs.event_ids)
    first_venue = first_interest + len(inputs.interest_vectors)
    first_user = first_venue + len(venue_names)
    first_shown = first_user + len(inputs.user_ids)
    size = first_shown + len(inputs.user_ids)
    sources, targets, weights = [], [], []

    def add_one_way(a, b, weight=1.0):
        sources.append(a)
        targets.append(b)
        weights.append(weight)

    def add_both_ways(a, b, weight=1.0):
        add_one_way(a, b, weight)
        add_one_way(b, a, weight)

    for e, venue in enumerate(inputs.venues):
        if venue:
            add_both_ways(e, first_venue + venue_index[venue])
    event_interest, event_event = content_edges(inputs)
    for e, i in event_interest:
        add_both_ways(e, first_interest + i)
    for e, other in event_event:
        add_both_ways(e, other)
    # A user's edges lead out only, so no one else's walk passes through them. Their stances reach only their friends,
    # through a node that leads nowhere else: not to their picks, not to their friends. The cards show the same.
    for u, i in inputs.picks:
        add_one_way(first_user + u, first_interest + i)
    for u, e, weight in inputs.stances:
        add_one_way(first_user + u, e, weight)
        add_one_way(first_shown + u, e, weight)
    # All of a user's friends together weigh like one interest pick. Weighed one each, friends would take most of a
    # walk's first step and put the same few friend-marked events on top for everyone.
    marked = {u for u, _, _ in inputs.stances}
    shown = [(u, friend) for u, friend in inputs.friends if friend in marked]
    count = np.bincount([u for u, _ in shown], minlength=len(inputs.user_ids))
    for u, friend in shown:
        add_one_way(first_user + u, first_shown + friend, 1 / count[u])
    return sparse.csr_matrix((weights, (sources, targets)), shape=(size, size))


def walk(step: sparse.csr_matrix, seed: np.ndarray) -> np.ndarray:
    mass = seed.copy()
    for _ in range(ITERATIONS):
        mass = RESTART_PROBABILITY * seed + (1 - RESTART_PROBABILITY) * (step @ mass)
    return mass


def transition(graph: sparse.csr_matrix) -> sparse.csr_matrix:
    """Column j spreads node j's mass over its edges by weight. Mass reaching a node with no edges out, such as an
    event someone marked with no venue or content, is lost, which scales a walk without changing its ranks."""
    out_weight = np.asarray(graph.sum(axis=1)).ravel()
    out_weight[out_weight == 0] = 1
    return (sparse.diags(1 / out_weight) @ graph).T.tocsr()


def scores(inputs: Inputs) -> Iterator[tuple[int, int, float]]:
    """(user, event, score): the walk's mass ranked among the user's candidates, 0 to 1. A user whose walk can't tell
    them apart gets no rows, which the feed reads as NEUTRAL for all."""
    graph = adjacency(inputs)
    first_user = graph.shape[0] - 2 * len(inputs.user_ids)
    with_edges = np.flatnonzero(np.diff(graph.indptr)[first_user:first_user + len(inputs.user_ids)]).tolist()
    step = transition(graph)
    for start in range(0, len(with_edges), USERS_PER_BATCH):
        yield from ranked(inputs, step, first_user, with_edges[start:start + USERS_PER_BATCH])


def ranked(inputs: Inputs, step: sparse.csr_matrix, first_user: int,
           users: list[int]) -> Iterator[tuple[int, int, float]]:
    seed = np.zeros((step.shape[0], len(users)))
    seed[[first_user + u for u in users], range(len(users))] = 1
    mass = walk(step, seed)
    for column, u in enumerate(users):
        allowed = inputs.not_ended.copy()
        allowed[list(inputs.hidden.get(u, ()))] = False
        events = np.flatnonzero(allowed)
        values = mass[events, column]
        if len(events) < 2 or values.min() == values.max():
            continue
        ranks = (rankdata(values) - 1) / (len(events) - 1)
        yield from ((u, int(e), float(r)) for e, r in zip(events, ranks))
