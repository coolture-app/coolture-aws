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
    ({"kids": 0.82, "concerts": 0.80, "stage": 0.70}, ["kids", "concerts"]),
    ({"kids": 0.82, "stage": 0.70}, ["kids", "stage"]),
    ({"kids": 0.82}, ["kids"]),
], ids=["far ahead of the best format", "any best format", "no other kind"])
def test_should_give_dla_dzieci_its_best_format_when_it_stands_alone(scores, expected):
    assert kinds.derive(scores) == expected


@pytest.mark.parametrize("scores, expected", [
    ({"kids": 0.805, "concerts": 0.80, "stage": 0.70}, ["kids", "concerts"]),
    ({"concerts": 0.80, "kids": 0.795, "stage": 0.70}, ["concerts"]),
    ({"concerts": 0.80, "stage": 0.799, "kids": 0.7985}, ["concerts", "stage", "kids"]),
    ({"concerts": 0.80, "stage": 0.70, "kids": 0.7985}, ["concerts", "kids"]),
], ids=["kept first", "removed when too far below the best other", "added last", "kept as the second"])
def test_should_keep_dla_dzieci_only_when_close_enough_to_the_best_other_kind(scores, expected):
    assert kinds.derive(scores) == expected
