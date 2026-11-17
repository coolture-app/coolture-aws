import numpy as np
import pytest

import ppr
from ppr import Inputs, adjacency, content_edges, scores

DIM, GROUPS, PER_GROUP = 12, 6, 4
# Each interest is an axis with its own events near it, and seeded noise so no two events are alike.
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
        not_ended=np.ones(N, dtype=bool),
        venues=[None] * N,
        post_average=EVENTS.mean(axis=0),
        interest_vectors=INTERESTS,
        user_ids=["u0"],
        picks=[],
        stances=[],
        friends=[],
        hidden={},
    )
    base.update(kw)
    return Inputs(**base)


def by_event(rows, user=0):
    return {e: s for u, e, s in rows if u == user}


def test_a_new_user_gets_the_events_of_their_interest_first():
    got = by_event(scores(inputs(picks=[(0, 1)])))
    top = sorted(got, key=got.get, reverse=True)[:PER_GROUP]
    assert set(top) == group(1)


def test_scores_are_ranks_from_0_to_1():
    got = by_event(scores(inputs(picks=[(0, 0)])))
    assert len(got) == N
    assert min(got.values()) == 0 and max(got.values()) == 1


def test_hidden_and_ended_events_get_no_score():
    not_ended = np.ones(N, dtype=bool)
    not_ended[5] = False
    got = by_event(scores(inputs(picks=[(0, 0)], not_ended=not_ended, hidden={0: {1}})))
    assert set(got) == set(range(N)) - {1, 5}


def test_a_user_with_no_edges_gets_no_rows():
    rows = list(scores(inputs(user_ids=["u0", "u1"], picks=[(0, 0)])))
    assert by_event(rows, user=0) and not by_event(rows, user=1)


def test_a_strangers_stances_move_no_ones_scores():
    # u0 and u1 are both going to event 0, and u1 to event 12 too, but they aren't friends.
    together = inputs(user_ids=["u0", "u1"], stances=[(0, 0, 2.0), (1, 0, 2.0), (1, 12, 2.0)], hidden={0: {0}})
    alone = inputs(user_ids=["u0", "u1"], stances=[(0, 0, 2.0)], hidden={0: {0}})
    assert by_event(scores(together)) == by_event(scores(alone))


def test_a_friends_stances_pull_their_events_up():
    stances = [(1, 12, 1.0)]
    friends = by_event(scores(inputs(user_ids=["u0", "u1"], picks=[(0, 0)], stances=stances, friends=[(0, 1)])))
    strangers = by_event(scores(inputs(user_ids=["u0", "u1"], picks=[(0, 0)], stances=stances)))
    assert friends[12] > strangers[12]


def test_a_friend_passes_on_their_stances_but_not_their_picks_or_their_friends():
    # u1 is u0's friend, picked interest 3 and is going to event 4; u2 is u1's friend, not u0's.
    full = inputs(user_ids=["u0", "u1", "u2"], picks=[(0, 0), (1, 3)], stances=[(1, 4, 2.0), (2, 12, 2.0)],
                  friends=[(0, 1), (1, 0), (1, 2), (2, 1)])
    stances_only = inputs(user_ids=["u0", "u1", "u2"], picks=[(0, 0)], stances=[(1, 4, 2.0)],
                          friends=[(0, 1), (1, 0)])
    assert by_event(scores(full)) == by_event(scores(stances_only))


def test_a_venue_links_its_events():
    venues = [None] * N
    venues[0] = venues[16] = "hall"
    with_venue = by_event(scores(inputs(stances=[(0, 0, 1.0)], hidden={0: {0}}, venues=venues)))
    without = by_event(scores(inputs(stances=[(0, 0, 1.0)], hidden={0: {0}})))
    assert with_venue[16] > without[16]


def test_batches_give_the_same_scores(monkeypatch):
    many = inputs(user_ids=["u0", "u1", "u2"], picks=[(0, 0), (1, 2), (2, 4)])
    whole = sorted(scores(many))
    monkeypatch.setattr(ppr, "USERS_PER_BATCH", 1)
    assert sorted(scores(many)) == whole


def test_ended_events_have_no_event_neighbours():
    not_ended = np.ones(N, dtype=bool)
    not_ended[list(group(0))] = False
    _, to_event = content_edges(inputs(not_ended=not_ended))
    assert not any(e in group(0) or f in group(0) for e, f in to_event)


def test_an_event_equal_to_the_average_post_gets_no_content_edges():
    vectors = EVENTS.copy()
    vectors[3] = EVENTS.mean(axis=0)
    to_interest, to_event = content_edges(inputs(event_vectors=vectors, post_average=vectors[3]))
    assert all(e != 3 for e, _ in to_interest) and all(3 not in pair for pair in to_event)


def test_a_single_event_still_links_to_interests():
    single = inputs(event_ids=["e0"], event_vectors=EVENTS[:1], not_ended=np.ones(1, dtype=bool), venues=[None])
    to_interest, to_event = content_edges(single)
    assert to_interest and not to_event


@pytest.mark.parametrize("chunk", [1, 5, 7])
def test_event_neighbours_dont_depend_on_the_chunk_size(monkeypatch, chunk):
    whole = content_edges(inputs())
    monkeypatch.setattr(ppr, "EVENTS_PER_BLOCK", chunk)
    assert content_edges(inputs()) == whole


def test_a_stronger_stance_pulls_harder():
    def scored(weight):
        return by_event(scores(inputs(stances=[(0, 0, 1.0), (0, 12, weight)], hidden={0: {0, 12}})))

    assert scored(2.0)[13] > scored(1.0)[13]


def test_what_every_interest_shares_doesnt_choose_an_events_interests():
    shift = np.random.default_rng(1).normal(size=DIM)
    assert content_edges(inputs(interest_vectors=INTERESTS + shift))[0] == content_edges(inputs())[0]


def test_the_walk_keeps_all_its_mass():
    friends = inputs(user_ids=["u0", "u1"], picks=[(0, 1)], stances=[(1, 12, 1.0)], friends=[(0, 1), (1, 0)])
    graph = adjacency(friends)
    seed = np.zeros((graph.shape[0], 1))
    seed[graph.shape[0] - 4] = 1
    mass = ppr.walk(ppr.transition(graph), seed)
    assert np.isfinite(mass).all() and (mass >= 0).all() and mass.sum() == pytest.approx(1)


def test_an_event_the_walk_cant_reach_ranks_last():
    vectors = EVENTS.copy()
    vectors[7] = EVENTS.mean(axis=0)  # no content edges, no venue, no stances: cut off
    got = by_event(scores(inputs(picks=[(0, 0)], event_vectors=vectors, post_average=vectors[7])))
    assert got[7] == 0


def test_matches_a_walk_over_edges_written_by_hand():
    # Two interests, so every event with content is near both; three upcoming events, all each other's neighbours;
    # event 3 has ended and shares a venue with event 0. u0 picked interest 0, is going to event 3 and is friends
    # with u1, who is interested in event 1.
    rng = np.random.default_rng(3)
    small = Inputs(event_ids=["e0", "e1", "e2", "e3"],
                   event_vectors=np.array([unit(v) for v in rng.normal(size=(4, 5))]),
                   not_ended=np.array([True, True, True, False]), venues=["hall", None, None, "hall"],
                   post_average=np.zeros(5), interest_vectors=np.array([unit(v) for v in rng.normal(size=(2, 5))]),
                   user_ids=["u0", "u1"], picks=[(0, 0)], stances=[(0, 3, 2.0), (1, 1, 1.5)],
                   friends=[(0, 1), (1, 0)], hidden={})
    both_ways = [("e0", "hall", 1), ("e3", "hall", 1), ("e0", "e1", 1), ("e0", "e2", 1), ("e1", "e2", 1)]
    both_ways += [(e, i, 1) for e in ("e0", "e1", "e2", "e3") for i in ("i0", "i1")]
    edges = {}
    for a, b, w in both_ways:
        edges.setdefault(a, {})[b] = w
        edges.setdefault(b, {})[a] = w
    # Out of users only; u1's stance reaches u0 through what u1 shows friends.
    edges["u0"] = {"i0": 1, "e3": 2, "u1 shown": 1}
    edges["u1 shown"] = {"e1": 1.5}

    restart = ppr.RESTART_PROBABILITY
    mass = {"u0": 1.0}
    for _ in range(500):
        moved = {"u0": restart}
        for node, m in mass.items():
            if node not in edges:
                continue
            total = sum(edges[node].values())
            for other, w in edges[node].items():
                moved[other] = moved.get(other, 0) + (1 - restart) * m * w / total
        mass = moved

    got = {small.event_ids[e]: s for e, s in by_event(scores(small)).items()}
    assert sorted(got, key=got.get) == sorted(["e0", "e1", "e2"], key=mass.get)
    assert len({round(mass[e], 9) for e in ("e0", "e1", "e2")}) == 3


def test_all_of_a_users_friends_weigh_like_one_edge_into_what_they_show():
    users = ["u0", "u1", "u2", "u3"]
    stances = [(1, 0, 1.5), (2, 0, 1.5), (3, 0, 1.5)]
    a = adjacency(inputs(user_ids=users, stances=stances, friends=[(0, 1), (0, 2), (0, 3), (1, 0)])).toarray()
    first_user, first_shown = a.shape[0] - 8, a.shape[0] - 4
    assert a[first_user, first_shown + 1:].sum() == pytest.approx(1)
    assert not a[first_user, first_user:first_shown].any() and not a[:, first_user:first_shown].any()
