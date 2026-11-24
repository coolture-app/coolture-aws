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


def test_model_name_is_the_same_everywhere():
    # On a mismatch the feed finds no vectors of its model and ranks with affinity neutral for everyone.
    recommender = Path(__file__).resolve().parents[2]
    sources = {
        "post-embedding/Dockerfile": r"^ARG EMBEDDING_MODEL=(\S+)",
        "post-embedding/src/settings.py": r'^\s*embedding_model: str = "(.+)"',
        "ppr/src/app.py": r'^EMBEDDING_MODEL = "(.+)"',
    }
    names = {path: re.search(rx, (recommender / path).read_text(encoding="utf-8"), re.M).group(1) for path, rx in sources.items()}
    names["FeedConfig.java"] = java(r'EMBEDDING_MODEL = "(.+)";')
    assert len(set(names.values())) == 1, names
