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
# (1 - RESTART_PROBABILITY) ** ITERATIONS < 1e-9: what the walk would still move is too little to swap two ranks.
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
    follows: Pairs  # (follower, followed)
    hidden: dict[int, set[int]]  # user: events the feed never shows them as new
    private: set[int]  # users who don't share their stances
    blocked: dict[int, set[int]]  # user: the users on the other side of their blocks


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
    """Only among events that haven't ended, as measured; ended ones keep their other edges. Each pair once."""
    k = min(EVENT_NEIGHBOURS, len(linked) - 1)
    if k < 1:
        return []
    pairs = set()
    # In blocks of rows, so memory doesn't grow with the square of the events.
    for start in range(0, len(linked), EVENTS_PER_BLOCK):
        block = np.arange(start, min(start + EVENTS_PER_BLOCK, len(linked)))
        similarity = events[linked[block]] @ events[linked].T
        similarity[np.arange(len(block)), block] = -np.inf  # an event isn't its own neighbour
        for row, neighbours in zip(block, np.argpartition(similarity, -k, axis=1)[:, -k:]):
            pairs.update(tuple(sorted((int(linked[row]), int(linked[n])))) for n in neighbours)
    return sorted(pairs)


def adjacency(inputs: Inputs) -> sparse.csr_matrix:
    """Nodes are the events, then the interests, the venues, and last the users."""
    venue_names = sorted({v for v in inputs.venues if v})
    venue_index = {name: j for j, name in enumerate(venue_names)}
    first_interest = len(inputs.event_ids)
    first_venue = first_interest + len(inputs.interest_vectors)
    first_user = first_venue + len(venue_names)
    size = first_user + len(inputs.user_ids)
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
    def add_from_user(u, node, weight=1.0):
        add_one_way(first_user + u, node, weight)
        # No walk but their own enters a user who doesn't share their stances, so it can't follow them to their events.
        if u not in inputs.private:
            add_one_way(node, first_user + u, weight)

    for u, i in inputs.picks:
        add_from_user(u, first_interest + i)
    for u, e, weight in inputs.stances:
        add_from_user(u, e, weight)
    # All of a user's friends together weigh like one interest pick. Each its own weight made friends half of the
    # walk's first step and put the same few friend-marked events on top for everyone.
    friends = np.bincount([follower for follower, _ in inputs.follows], minlength=len(inputs.user_ids))
    for follower, followed in inputs.follows:
        add_one_way(first_user + follower, first_user + followed, 1 / friends[follower])
    return sparse.csr_matrix((weights, (sources, targets)), shape=(size, size))


def walk(step: sparse.csr_matrix, seed: np.ndarray) -> np.ndarray:
    mass = seed.copy()
    for _ in range(ITERATIONS):
        mass = RESTART_PROBABILITY * seed + (1 - RESTART_PROBABILITY) * (step @ mass)
    return mass


def candidates(inputs: Inputs, user: int) -> np.ndarray:
    allowed = inputs.not_ended.copy()
    allowed[list(inputs.hidden.get(user, ()))] = False
    return np.flatnonzero(allowed)


def transition(graph: sparse.csr_matrix) -> sparse.csr_matrix:
    """Column j spreads node j's mass over its edges by weight. Mass that reaches a node with none, which only a
    private or blocked user's edges lead to, is lost; sending it back to the start instead would scale every score of
    that walk alike and change no rank."""
    out_weight = np.asarray(graph.sum(axis=1)).ravel()
    out_weight[out_weight == 0] = 1
    return (sparse.diags(1 / out_weight) @ graph).T.tocsr()


def scores(inputs: Inputs) -> Iterator[tuple[int, int, float]]:
    """(user, event, score): the walk's mass ranked among the user's candidates, 0 to 1. A user whose walk can't tell
    them apart gets no rows, which the feed reads as NEUTRAL for all."""
    graph = adjacency(inputs)
    first_user = graph.shape[0] - len(inputs.user_ids)
    users_with_edges = [u for u in range(len(inputs.user_ids)) if graph[first_user + u].nnz]
    unblocked = [u for u in users_with_edges if not inputs.blocked.get(u)]
    step = transition(graph)
    for start in range(0, len(unblocked), USERS_PER_BATCH):
        batch = unblocked[start:start + USERS_PER_BATCH]
        yield from ranked(inputs, batch, walk(step, starts(graph.shape[0], [first_user + u for u in batch])))

    # Each over a graph without the people on the other side of their blocks, whose stances would otherwise still
    # reach them through a shared event. Few users block anyone, so walking them one by one costs little.
    for u in users_with_edges:
        if inputs.blocked.get(u):
            keep = np.ones(graph.shape[0])
            keep[[first_user + other for other in inputs.blocked[u]]] = 0
            without = sparse.diags(keep) @ graph @ sparse.diags(keep)
            yield from ranked(inputs, [u], walk(transition(without.tocsr()), starts(graph.shape[0], [first_user + u])))


def starts(size: int, nodes: list[int]) -> np.ndarray:
    seed = np.zeros((size, len(nodes)))
    seed[nodes, range(len(nodes))] = 1
    return seed


def ranked(inputs: Inputs, users: list[int], mass: np.ndarray) -> Iterator[tuple[int, int, float]]:
    for column, u in enumerate(users):
        events = candidates(inputs, u)
        values = mass[events, column]
        if len(events) < 2 or values.min() == values.max():
            continue
        ranks = (rankdata(values) - 1) / (len(events) - 1)
        yield from ((u, int(e), float(r)) for e, r in zip(events, ranks))
