import re
from pathlib import Path

import app
import ppr

FEED_CONFIG = (Path(__file__).resolve().parents[3] / "aws/lambdas/post-read/src/main/java/com/coolture/feed/FeedConfig.java"
               ).read_text(encoding="utf-8")


def java(pattern: str) -> str:
    return re.search(pattern, FEED_CONFIG).group(1)


def test_the_job_reads_stances_as_the_feed_does():
    # A difference would score users from other stances or other ages than the vector the feed builds from them.
    weights = re.findall(r'"(\w+)", ([\d.]+)', java(r"STANCE_WEIGHTS = Map\.of\(([^)]*)\)"))
    assert app.STANCE_WEIGHTS.items() <= {k: float(v) for k, v in weights}.items()
    assert app.STANCE_HALF_LIFE_DAYS == float(java(r"STANCE_HALF_LIFE_DAYS = ([\d.]+);"))
    assert app.STANCE_MAX_AGE_DAYS == int(java(r"STANCE_MAX_AGE_DAYS = (\d+);"))


def test_runs_are_deleted_when_the_feed_stops_reading_them():
    assert app.RUN_MAX_AGE_HOURS == int(java(r"PPR_RUN_MAX_AGE_HOURS = (\d+);"))


def test_rows_are_written_only_above_what_the_feed_reads_for_none():
    assert ppr.NEUTRAL == float(java(r"double NEUTRAL = ([\d.]+);"))
