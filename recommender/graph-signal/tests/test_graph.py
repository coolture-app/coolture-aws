import numpy as np

from graph import Inputs, adjacency, content_edges, scores

DIM, GROUPS, PER_GROUP = 12, 6, 4
# Six interests along six axes, and four events near each, with a little seeded noise so no two are alike.
INTERESTS = np.eye(DIM)[:GROUPS]
_noise = np.random.default_rng(7).normal(scale=0.15, size=(GROUPS * PER_GROUP, DIM))


def unit(v):
    v = np.asarray(v, dtype=float)
    return v / np.linalg.norm(v)


EVENTS = np.array([unit(INTERESTS[e // PER_GROUP] + _noise[e]) for e in range(GROUPS * PER_GROUP)])
N = len(EVENTS)


def group(g):
    return set(range(g * PER_GROUP, (g + 1) * PER_GROUP))


def inputs(**kw):
    base = dict(
        event_ids=[f"e{i}" for i in range(N)],
        event_vectors=EVENTS,
        scorable=np.ones(N, dtype=bool),
        venues=[None] * N,
        post_average=EVENTS.mean(axis=0),
        interest_vectors=INTERESTS,
        user_ids=["u0"],
    )
    base.update(kw)
    return Inputs(**base)


def by_event(rows, user=0):
    rows = list(rows)
    return {e: s for u, e, s in rows if u == user}


def test_a_new_user_gets_the_events_of_their_interest_first():
    got = by_event(scores(inputs(picks=[(0, 1)])))
    top = sorted(got, key=got.get, reverse=True)[:PER_GROUP]
    assert set(top) == group(1)


def test_scores_are_ranks_from_0_to_1():
    got = by_event(scores(inputs(picks=[(0, 0)])))
    assert len(got) == N
    assert min(got.values()) == 0 and max(got.values()) == 1
    assert all(0 <= s <= 1 for s in got.values())


def test_hidden_and_ended_events_get_no_score():
    scorable = np.ones(N, dtype=bool)
    scorable[5] = False
    got = by_event(scores(inputs(picks=[(0, 0)], scorable=scorable, hidden={0: {1}})))
    assert set(got) == set(range(N)) - {1, 5}


def test_a_user_with_no_edges_gets_no_rows():
    rows = list(scores(inputs(user_ids=["u0", "u1"], picks=[(0, 0)])))
    assert by_event(rows, user=0) and not by_event(rows, user=1)


def test_others_reactions_carry_over():
    # u0 and u1 both like event 0; u1 also likes event 12, of another kind. u0's walk reaches it through u1.
    stances = [(0, 0, 1.0), (1, 0, 1.0), (1, 12, 1.0)]
    got = by_event(scores(inputs(user_ids=["u0", "u1"], stances=stances, hidden={0: {0}, 1: {0, 12}})))
    alone = by_event(scores(inputs(user_ids=["u0", "u1"], stances=[(0, 0, 1.0)], hidden={0: {0}})))
    assert got[12] > alone[12]


def test_a_follow_points_one_way():
    a = adjacency(inputs(user_ids=["u0", "u1"], follows=[(0, 1)])).toarray()
    users = a.shape[0] - 2
    assert a[users, users + 1] == 1 and a[users + 1, users] == 0


def test_following_someone_pulls_their_events_up():
    stances = [(1, 12, 1.0)]
    followed = by_event(scores(inputs(user_ids=["u0", "u1"], picks=[(0, 0)], stances=stances, follows=[(0, 1)])))
    not_followed = by_event(scores(inputs(user_ids=["u0", "u1"], picks=[(0, 0)], stances=stances)))
    assert followed[12] > not_followed[12]


def test_a_venue_links_its_events():
    venues = [None] * N
    venues[0] = venues[16] = "hall"
    with_venue = by_event(scores(inputs(stances=[(0, 0, 1.0)], hidden={0: {0}}, venues=venues)))
    without = by_event(scores(inputs(stances=[(0, 0, 1.0)], hidden={0: {0}})))
    assert with_venue[16] > without[16]


def test_batches_give_the_same_scores(monkeypatch):
    import graph

    many = inputs(user_ids=["u0", "u1", "u2"], picks=[(0, 0), (1, 2), (2, 4)])
    whole = sorted(scores(many))
    monkeypatch.setattr(graph, "BATCH", 1)
    assert sorted(scores(many)) == whole


def test_ended_events_have_no_event_neighbours():
    scorable = np.ones(N, dtype=bool)
    scorable[list(group(0))] = False
    _, to_event = content_edges(inputs(scorable=scorable))
    assert not any(e in group(0) or f in group(0) for e, f in to_event)


def test_a_single_event_still_links_to_interests():
    single = inputs(event_ids=["e0"], event_vectors=EVENTS[:1], scorable=np.ones(1, dtype=bool), venues=[None])
    to_interest, to_event = content_edges(single)
    assert to_interest and not to_event
