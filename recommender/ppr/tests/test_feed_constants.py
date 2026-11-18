import re
from pathlib import Path

import app

LAMBDAS = Path(__file__).resolve().parents[3] / "aws/lambdas"
FEED_CONFIG = (LAMBDAS / "post-read/src/main/java/com/coolture/feed/FeedConfig.java").read_text(encoding="utf-8")
EVENT_TIME = (LAMBDAS / "common/src/main/java/com/coolture/common/db/EventTime.java").read_text(encoding="utf-8")


def java(pattern: str, source: str = FEED_CONFIG) -> str:
    return re.search(pattern, source).group(1)


def java_string(name: str, source: str) -> str:
    """A Java String constant built from literals and int constants of the same class joined by +."""
    expression = java(rf"String {name} =\s*([^;]+);", source)
    parts = re.findall(r'"((?:[^"\\]|\\.)*)"|(\w+)', expression)
    return "".join(literal or java(rf"int {constant} = (\d+);", source) for literal, constant in parts)


def test_the_job_reads_stances_and_events_as_the_feed_does():
    # A difference would score users from other stances or other ages than the vector the feed builds from them.
    weights = re.findall(r'"(\w+)", ([\d.]+)', java(r"STANCE_WEIGHTS = Map\.of\(([^)]*)\)"))
    assert app.STANCE_WEIGHTS.items() <= {k: float(v) for k, v in weights}.items()
    assert app.STANCE_HALF_LIFE_DAYS == float(java(r"STANCE_HALF_LIFE_DAYS = ([\d.]+);"))
    assert app.STANCE_MAX_AGE_DAYS == int(java(r"STANCE_MAX_AGE_DAYS = (\d+);"))
    assert app.DEFAULT_EVENT_HOURS == int(java(r"DEFAULT_EVENT_HOURS = (\d+);", EVENT_TIME))
    assert app.EVENT_END == java_string("END", EVENT_TIME)


def test_runs_are_deleted_when_the_feed_stops_reading_them():
    assert app.RUN_MAX_AGE_HOURS == int(java(r"PPR_RUN_MAX_AGE_HOURS = (\d+);"))
