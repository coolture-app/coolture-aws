"""Personalised PageRank from each user through their friends' stances to events like them. No I/O."""
from collections.abc import Iterator
from dataclasses import dataclass
from uuid import UUID

import numpy as np
from scipy import sparse
from scipy.stats import rankdata

EVENT_NEIGHBOURS = 5
RESTART_PROBABILITY = 0.3
# Enough that (1 - RESTART_PROBABILITY) ** ITERATIONS, what the walk would still move, can't swap two ranks.
ITERATIONS = 60
USERS_PER_BATCH = 256
EVENTS_PER_BLOCK = 1024

Pairs = list[tuple[int, int]]


@dataclass(frozen=True)
class Inputs:
    """Users and events are numbered by their position in user_ids and event_ids. Vectors are one model's, unit
    length. Only events that haven't ended get scores."""

    event_ids: list[UUID]
    event_vectors: np.ndarray
    not_ended: np.ndarray
    venues: list[str | None]
    post_average: np.ndarray
    user_ids: list[UUID]
    stances: list[tuple[int, int, float]]  # (user, event, weight)
    friends: Pairs  # (user, friend), one row per direction
    hidden: dict[int, set[int]]  # user: events the feed never shows them as new


def centred_and_normalised(vectors: np.ndarray, average: np.ndarray) -> np.ndarray:
    out = vectors - average
    lengths = np.linalg.norm(out, axis=1, keepdims=True)
    lengths[lengths == 0] = 1
    return out / lengths


def similar_events(inputs: Inputs) -> Pairs:
    """Each event that hasn't ended with its nearest by content, each pair once. By rank, not by a cosine threshold:
    the model's cosines sit in a narrow band. An event equal to the average post has none."""
    events = centred_and_normalised(inputs.event_vectors, inputs.post_average)
    linked = np.flatnonzero(inputs.not_ended & events.any(axis=1))
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
    """Nodes are the events, then the venues, the users, and last what each user shows their friends."""
    venue_names = sorted({v for v in inputs.venues if v})
    venue_index = {name: j for j, name in enumerate(venue_names)}
    first_venue = len(inputs.event_ids)
    first_user = first_venue + len(venue_names)
    first_shown = first_user + len(inputs.user_ids)
    size = first_shown + len(inputs.user_ids)
    sources, targets, weights = [], [], []

    def add_one_way(a, b, weight=1.0):
        sources.append(a)
        targets.append(b)
        weights.append(weight)

    def add_both_ways(a, b):
        add_one_way(a, b)
        add_one_way(b, a)

    for e, venue in enumerate(inputs.venues):
        if venue:
            add_both_ways(e, first_venue + venue_index[venue])
    for e, other in similar_events(inputs):
        add_both_ways(e, other)
    # A user's own picks and stances are in the feed's affinity already, so their walk leaves only along friend edges,
    # each friend alike. It reaches a friend's interested and going, as the cards show them, and nothing past them.
    for u, e, weight in inputs.stances:
        add_one_way(first_shown + u, e, weight)
    for u, friend in inputs.friends:
        add_one_way(first_user + u, first_shown + friend)
    return sparse.csr_matrix((weights, (sources, targets)), shape=(size, size))


def walk(step: sparse.csr_matrix, seed: np.ndarray) -> np.ndarray:
    mass = seed.copy()
    for _ in range(ITERATIONS):
        mass = RESTART_PROBABILITY * seed + (1 - RESTART_PROBABILITY) * (step @ mass)
    return mass


def transition(graph: sparse.csr_matrix) -> sparse.csr_matrix:
    """Column j spreads node j's mass over its edges by weight. Mass reaching a node with no edges out, such as a
    friend who marked nothing, is lost, which scales a walk without changing its ranks."""
    out_weight = np.asarray(graph.sum(axis=1)).ravel()
    out_weight[out_weight == 0] = 1
    return (sparse.diags(1 / out_weight) @ graph).T.tocsr()


def scores(inputs: Inputs) -> Iterator[tuple[int, int, float]]:
    """(user, event, score): the walk's mass ranked among the user's candidates, 0 to 1. A user whose walk can't tell
    them apart, such as one whose friends marked nothing, gets no rows, which the feed reads as NEUTRAL for all."""
    graph = adjacency(inputs)
    first_user = graph.shape[0] - 2 * len(inputs.user_ids)
    with_friends = np.flatnonzero(np.diff(graph.indptr)[first_user:first_user + len(inputs.user_ids)]).tolist()
    step = transition(graph)
    for start in range(0, len(with_friends), USERS_PER_BATCH):
        yield from ranked(inputs, step, first_user, with_friends[start:start + USERS_PER_BATCH])


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
