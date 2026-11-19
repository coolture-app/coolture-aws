import numpy as np
import pytest

import ppr
from ppr import NEUTRAL, Inputs, adjacency, scores, seeds, similar_events, written

DIM, GROUPS, PER_GROUP = 12, 6, 4
# Each group of events sits along its own axis, with seeded noise so no two events are alike.
_noise = np.random.default_rng(7).normal(scale=0.15, size=(GROUPS * PER_GROUP, DIM))


def unit(v):
    v = np.asarray(v, dtype=float)
    return v / np.linalg.norm(v)


EVENTS = np.array([unit(np.eye(DIM)[e // PER_GROUP] + _noise[e]) for e in range(GROUPS * PER_GROUP)])
N = len(EVENTS)
# u0 and u1 are friends; u1 is going to event 0.
FRIENDS = dict(user_ids=["u0", "u1"], stances=[(1, 0, 2.0)], friends=[(0, 1), (1, 0)])


def group(g):
    return set(range(g * PER_GROUP, (g + 1) * PER_GROUP))


def inputs(**kw):
    base = dict(
        event_ids=[f"e{i}" for i in range(N)],
        event_vectors=EVENTS,
        not_ended=np.ones(N, dtype=bool),
        venues=[None] * N,
        post_average=EVENTS.mean(axis=0),
        user_ids=["u0"],
        stances=[],
        friends=[],
    )
    base.update(kw)
    return Inputs(**base)


def by_event(rows, user=0):
    return {e: s for u, e, s in rows if u == user}


def test_a_friends_event_and_those_like_it_come_first():
    got = by_event(scores(inputs(**FRIENDS)))
    alike, rest = group(0) - {0}, set(got) - group(0)
    assert max(got, key=got.get) == 0
    assert np.mean([got[e] for e in alike]) > np.mean([got[e] for e in rest])


def test_scores_are_ranks_from_0_to_1():
    got = by_event(scores(inputs(**FRIENDS)))
    assert len(got) == N and min(got.values()) == 0 and max(got.values()) == 1


def test_ended_events_get_no_score():
    not_ended = np.ones(N, dtype=bool)
    not_ended[5] = False
    got = by_event(scores(inputs(**FRIENDS, not_ended=not_ended)))
    assert set(got) == set(range(N)) - {5}


def test_written_are_the_ranks_above_neutral_with_fewer_events_than_twice_the_cap():
    every = by_event(scores(inputs(**FRIENDS)))
    assert N < 2 * ppr.SCORES_PER_USER
    assert by_event(written(scores(inputs(**FRIENDS)))) == {e: s for e, s in every.items() if s > NEUTRAL}


def test_written_leaves_out_events_the_walk_cant_reach():
    vectors = EVENTS.copy()
    vectors[[6, 7]] = EVENTS.mean(axis=0)
    got = by_event(written(scores(inputs(**FRIENDS, event_vectors=vectors, post_average=EVENTS.mean(axis=0)))))
    assert 6 not in got and 7 not in got


def test_written_keeps_each_users_highest_scores_up_to_the_cap(monkeypatch):
    many = inputs(user_ids=["u0", "u1", "u2"], stances=[(0, 4, 2.0), (1, 8, 2.0), (2, 12, 2.0)],
                  friends=[(0, 1), (1, 2), (2, 0)])
    every = list(scores(many))
    monkeypatch.setattr(ppr, "SCORES_PER_USER", 3)
    got = list(written(every))
    for user in range(3):
        mine = by_event(every, user)
        assert by_event(got, user) == {e: mine[e] for e in sorted(mine, key=lambda e: (-mine[e], e))[:3]}


def test_a_user_whose_friends_marked_nothing_gets_no_rows():
    rows = list(scores(inputs(**FRIENDS)))
    assert by_event(rows, user=0) and not by_event(rows, user=1)


def test_a_users_own_stances_dont_move_their_scores():
    own = inputs(**{**FRIENDS, "stances": FRIENDS["stances"] + [(0, 12, 2.0)]})
    assert by_event(scores(own)) == by_event(scores(inputs(**FRIENDS)))


def test_a_strangers_stances_move_no_ones_scores():
    with_stranger = inputs(**{**FRIENDS, "user_ids": ["u0", "u1", "u2"],
                              "stances": FRIENDS["stances"] + [(2, 0, 2.0), (2, 12, 2.0)]})
    assert by_event(scores(with_stranger)) == by_event(scores(inputs(**FRIENDS)))


def test_a_friends_friends_dont_reach_you():
    # u2 is u1's friend, not u0's, and is going to event 12.
    further = inputs(**{**FRIENDS, "user_ids": ["u0", "u1", "u2"], "stances": FRIENDS["stances"] + [(2, 12, 2.0)],
                        "friends": FRIENDS["friends"] + [(1, 2), (2, 1)]})
    assert by_event(scores(further)) == by_event(scores(inputs(**FRIENDS)))


def test_a_shared_friend_doesnt_pass_on_their_other_friends():
    # u0 and u2 are both friends of u1; u3 is u2's friend, not u0's, and is going to events 12 and 13.
    shared = inputs(user_ids=["u0", "u1", "u2", "u3"], stances=[(1, 0, 2.0), (3, 12, 2.0), (3, 13, 2.0)],
                    friends=[(0, 1), (1, 0), (1, 2), (2, 1), (2, 3), (3, 2)])
    alone = inputs(user_ids=["u0", "u1"], stances=[(1, 0, 2.0)], friends=[(0, 1), (1, 0)])
    assert by_event(scores(shared)) == by_event(scores(alone))


def test_a_venue_links_a_friends_event_to_others_there():
    venues = [None] * N
    venues[0] = venues[16] = "hall"
    assert by_event(scores(inputs(**FRIENDS, venues=venues)))[16] > by_event(scores(inputs(**FRIENDS)))[16]


def test_a_friend_who_marked_many_events_gets_less_on_each_but_isnt_drowned_out():
    # u1 is going to event 0; u2 to events 12, 13 and 14.
    two = inputs(user_ids=["u0", "u1", "u2"], stances=[(1, 0, 2.0), (2, 12, 2.0), (2, 13, 2.0), (2, 14, 2.0)],
                 friends=[(0, 1), (0, 2)])
    got = by_event(scores(two))
    assert got[0] > max(got[12], got[13], got[14])
    assert min(got[12], got[13], got[14]) > max(got[e] for e in group(0) - {0})


def test_a_friends_old_stance_counts_less_than_anothers_fresh_one():
    # u1 was interested in event 12 98 days ago; u2 is going to event 0 now.
    old = 1.5 * 0.5 ** (98 / 14)
    got = by_event(scores(inputs(user_ids=["u0", "u1", "u2"], stances=[(1, 12, old), (2, 0, 2.0)],
                                 friends=[(0, 1), (0, 2)])))
    assert got[12] < max(got[e] for e in group(0) - {0})


@pytest.mark.parametrize("going, interested", [(0, 12), (12, 0)])
def test_going_pulls_harder_than_interested(going, interested):
    got = by_event(scores(inputs(**{**FRIENDS, "stances": [(1, interested, 1.5), (1, going, 2.0)]})))
    assert got[going] > got[interested]


def test_batches_give_the_same_scores(monkeypatch):
    many = inputs(user_ids=["u0", "u1", "u2"], stances=[(0, 4, 2.0), (1, 8, 2.0), (2, 12, 2.0)],
                  friends=[(0, 1), (1, 2), (2, 0)])
    whole = sorted(scores(many))
    monkeypatch.setattr(ppr, "USERS_PER_BATCH", 1)
    assert sorted(scores(many)) == whole


def test_ended_events_have_no_similar_events():
    not_ended = np.ones(N, dtype=bool)
    not_ended[list(group(0))] = False
    assert not any(e in group(0) or f in group(0) for e, f in similar_events(inputs(not_ended=not_ended)))


def test_an_event_equal_to_the_average_post_has_no_similar_events():
    vectors = EVENTS.copy()
    vectors[3] = EVENTS.mean(axis=0)
    assert all(3 not in pair for pair in similar_events(inputs(event_vectors=vectors, post_average=vectors[3])))


def test_a_single_event_has_no_similar_events():
    single = inputs(event_ids=["e0"], event_vectors=EVENTS[:1], not_ended=np.ones(1, dtype=bool), venues=[None])
    assert similar_events(single) == []


@pytest.mark.parametrize("block", [1, 5, 7])
def test_similar_events_dont_depend_on_the_block_size(monkeypatch, block):
    whole = similar_events(inputs())
    monkeypatch.setattr(ppr, "EVENTS_PER_BLOCK", block)
    assert similar_events(inputs()) == whole


def test_the_walk_keeps_all_its_mass():
    graph = adjacency(inputs(**FRIENDS))
    seed = seeds(inputs(**FRIENDS), graph.shape[0])[:, [0]].toarray()
    mass = ppr.walk(ppr.transition(graph), seed)
    assert np.isfinite(mass).all() and (mass >= 0).all() and mass.sum() == pytest.approx(1)


def test_a_start_splits_friends_by_square_root_and_a_friends_events_by_weight():
    # u1 marked 4 in all, u2 1, u3 nothing: u1 gets 2/3 of u0's start, u2 1/3. u0's own stance starts nothing.
    start = seeds(inputs(user_ids=["u0", "u1", "u2", "u3"], friends=[(0, 1), (0, 2), (0, 3)],
                         stances=[(1, 0, 3.0), (1, 1, 1.0), (2, 1, 1.0), (0, 5, 2.0)]), N).toarray()
    want = np.zeros(N)
    want[0], want[1] = 2 / 3 * 3 / 4, 2 / 3 * 1 / 4 + 1 / 3
    assert start[:, 0] == pytest.approx(want)
    assert not start[:, 1:].any()


def test_events_the_walk_cant_reach_tie_at_the_bottom():
    vectors = EVENTS.copy()
    vectors[[6, 7]] = EVENTS.mean(axis=0)
    got = by_event(scores(inputs(**FRIENDS, event_vectors=vectors, post_average=EVENTS.mean(axis=0))))
    assert got[6] == got[7] == min(got.values())


def test_a_user_whose_walk_ties_every_candidate_gets_no_rows():
    # The friend's only event has ended and has no venue and no content, so the walk goes nowhere from it.
    vectors = EVENTS.copy()
    vectors[5] = EVENTS.mean(axis=0)
    not_ended = np.ones(N, dtype=bool)
    not_ended[5] = False
    assert not list(scores(inputs(**{**FRIENDS, "stances": [(1, 5, 2.0)]}, event_vectors=vectors,
                                  post_average=vectors[5], not_ended=not_ended)))


def test_matches_a_walk_over_edges_written_by_hand():
    # Three upcoming events, all each other's neighbours; event 3 has ended and shares a venue with event 0. u0 is
    # friends with u1, who is going to event 3 and interested in event 1.
    small = Inputs(event_ids=["e0", "e1", "e2", "e3"],
                   event_vectors=np.array([unit(v) for v in np.random.default_rng(3).normal(size=(4, 5))]),
                   not_ended=np.array([True, True, True, False]), venues=["hall", None, None, "hall"],
                   post_average=np.zeros(5), user_ids=["u0", "u1"], stances=[(1, 3, 2.0), (1, 1, 1.5)],
                   friends=[(0, 1), (1, 0)])
    edges = {}
    for a, b in [("e0", "hall"), ("e3", "hall"), ("e0", "e1"), ("e0", "e2"), ("e1", "e2")]:
        edges.setdefault(a, {})[b] = 1
        edges.setdefault(b, {})[a] = 1
    # The user and what their friend shows are nodes here. No edge leads into either, so this is the code's walk, which
    # starts on the friend's events, scaled by 0.7 squared, with the same ranks.
    edges["u0"] = {"u1 shown": 1}
    edges["u1 shown"] = {"e3": 2, "e1": 1.5}

    restart = ppr.RESTART_PROBABILITY
    mass = {"u0": 1.0}
    for _ in range(500):
        moved = {"u0": restart}
        for node, m in mass.items():
            total = sum(edges[node].values())
            for other, w in edges[node].items():
                moved[other] = moved.get(other, 0) + (1 - restart) * m * w / total
        mass = moved

    got = {small.event_ids[e]: s for e, s in by_event(scores(small)).items()}
    assert sorted(got, key=got.get) == sorted(["e0", "e1", "e2"], key=mass.get)
    assert len({round(mass[e], 9) for e in ("e0", "e1", "e2")}) == 3
