"""Personalised PageRank for each user, started on their friends' stances, to events like them. No I/O."""
from collections.abc import Iterator
from dataclasses import dataclass
from uuid import UUID

import numpy as np
from scipy import sparse
from scipy.stats import rankdata

EVENT_NEIGHBOURS = 5
RESTART_PROBABILITY = 0.3
# Enough that the mass ends within about (1 - RESTART_PROBABILITY) ** ITERATIONS of the exact walk's, so only events
# that near-tie, closer than that, can swap ranks.
ITERATIONS = 60
USERS_PER_BATCH = 256
# The feed reads an event with no row as NEUTRAL, so the walk lifts the events nearest friends' marks and pushes no event
# below where the feed without PageRank puts it. Ranking every event cost the most when friends' taste differs.
SCORES_PER_USER = 50
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
    friends: Pairs  # (user, friend who shares their stances)


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
    """Nodes are the events, then the venues. No user is a node, so no walk can pass through one."""
    venue_names = sorted({v for v in inputs.venues if v})
    venue_index = {name: j for j, name in enumerate(venue_names)}
    first_venue = len(inputs.event_ids)
    size = first_venue + len(venue_names)
    sources, targets = [], []

    def add_both_ways(a, b):
        sources.extend((a, b))
        targets.extend((b, a))

    for e, venue in enumerate(inputs.venues):
        if venue:
            add_both_ways(e, first_venue + venue_index[venue])
    for e, other in similar_events(inputs):
        add_both_ways(e, other)
    return sparse.csr_matrix((np.ones(len(sources)), (sources, targets)), shape=(size, size))


def shares(weights: sparse.csc_matrix) -> sparse.csc_matrix:
    """Each column divided by its total. A column of zeros stays."""
    totals = np.asarray(weights.sum(axis=0)).ravel()
    totals[totals == 0] = 1
    return weights @ sparse.diags(1 / totals, format="csc")


def seeds(inputs: Inputs, nodes: int) -> sparse.csc_matrix:
    """Column u is where user u's walk starts and restarts: their friends' interested and going, as the cards show
    them. Empty for a user whose friends marked nothing. A user's own picks and stances are in the feed's affinity
    already, so they start no walk."""
    users = len(inputs.user_ids)
    stance_users, events, weights = zip(*inputs.stances) if inputs.stances else ((), (), ())
    shown = sparse.csc_matrix((weights, (events, stance_users)), shape=(nodes, users))
    marked = np.asarray(shown.sum(axis=0)).ravel()
    # By the square root of a friend's faded stances: fresh ones count more than old ones, and a friend who marks more
    # gets more say but less on each event, so an active friend is neither drowned out nor takes the walk over.
    user, friend = np.array(inputs.friends, dtype=int).reshape(-1, 2).T
    friends = sparse.csc_matrix((np.sqrt(marked[friend]), (friend, user)), shape=(users, users))
    return shares(shown) @ shares(friends)


def walk(step: sparse.csr_matrix, seed: np.ndarray) -> np.ndarray:
    mass = seed.copy()
    for _ in range(ITERATIONS):
        mass = RESTART_PROBABILITY * seed + (1 - RESTART_PROBABILITY) * (step @ mass)
    return mass


def transition(graph: sparse.csr_matrix) -> sparse.csr_matrix:
    """Column j spreads node j's mass over its edges by weight. Mass reaching a node with no edges out, such as an ended
    event with no venue, is lost, which scales a walk without changing its ranks."""
    return shares(graph.T).tocsr()


def scores(inputs: Inputs) -> Iterator[tuple[int, int, float]]:
    """(user, event, score): the walk's mass ranked among every event that hasn't ended, 0 to 1, for the
    SCORES_PER_USER events with the most. The feed alone leaves out a user's own, reacted and blocked posts, so its
    rules have one copy. A user whose walk can't tell the events apart, such as one whose friends marked nothing, gets
    no rows, which the feed reads as NEUTRAL for all."""
    step = transition(adjacency(inputs))
    seed = seeds(inputs, step.shape[0])
    with_friends = np.flatnonzero(np.diff(seed.indptr))
    for start in range(0, len(with_friends), USERS_PER_BATCH):
        users = with_friends[start:start + USERS_PER_BATCH]
        mass = walk(step, seed[:, users].toarray())
        for column, u in enumerate(users.tolist()):
            yield from ranked(inputs, u, mass[:, column])


def ranked(inputs: Inputs, user: int, mass: np.ndarray) -> Iterator[tuple[int, int, float]]:
    events = np.flatnonzero(inputs.not_ended)
    values = mass[events]
    if len(events) < 2 or values.min() == values.max():
        return
    ranks = (rankdata(values) - 1) / (len(events) - 1)
    kept = np.lexsort((events, -values))[:SCORES_PER_USER]
    yield from ((user, int(events[i]), float(ranks[i])) for i in kept)
