import pytest

import kinds


@pytest.mark.parametrize("scores, expected", [
    ({"concerts": 0.80, "stage": 0.79, "kids": 0.70}, ["concerts"]),
    ({"concerts": 0.80, "stage": 0.795, "kids": 0.70}, ["concerts", "stage"]),
    ({"stage": 0.795, "concerts": 0.80, "kids": 0.70}, ["concerts", "stage"]),
], ids=["best only", "second within the margin", "best first whatever the order"])
def test_should_keep_the_best_kind_and_the_second_within_the_margin(scores, expected):
    assert kinds.derive(scores) == expected


@pytest.mark.parametrize("scores, expected", [
    ({"kids": 0.805, "concerts": 0.80, "stage": 0.70}, ["kids", "concerts"]),
    ({"concerts": 0.80, "kids": 0.795, "stage": 0.70}, ["concerts"]),
    ({"concerts": 0.80, "stage": 0.799, "kids": 0.7985}, ["concerts", "stage", "kids"]),
    ({"concerts": 0.80, "stage": 0.70, "kids": 0.7985}, ["concerts", "kids"]),
], ids=["kept first", "removed when too far below the best other", "added last", "kept as the second"])
def test_should_keep_dla_dzieci_only_when_close_enough_to_the_best_other_kind(scores, expected):
    assert kinds.derive(scores) == expected


def test_should_take_the_dot_product_as_the_cosine():
    assert kinds.cosines([0.6, 0.8], {"concerts": [1.0, 0.0], "stage": [0.0, 1.0]}) == {"concerts": 0.6, "stage": 0.8}
