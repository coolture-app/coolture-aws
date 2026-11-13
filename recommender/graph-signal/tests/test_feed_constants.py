import re
from pathlib import Path

from settings import Settings

FEED_CONFIG = (Path(__file__).resolve().parents[3]
               / "aws/lambdas/post-read/src/main/java/com/coolture/feed/FeedConfig.java").read_text()


def java(pattern: str) -> str:
    return re.search(pattern, FEED_CONFIG).group(1)


def test_the_job_reads_stances_as_the_feed_does():
    # A difference would score users from other stances or other ages than the vector the feed builds from them.
    s = Settings(db_username="test")
    assert s.embedding_model == java(r'EMBEDDING_MODEL = "(.+)";')
    stances = dict(re.findall(r'"(\w+)", ([\d.]+)', java(r"STANCE_WEIGHTS = Map\.of\(([^)]*)\)")))
    assert {k: float(v) for k, v in stances.items()} == {
        "LIKE": s.weight_like, "INTERESTED": s.weight_interested, "TAKES_PART": s.weight_takes_part}
    assert s.stance_half_life_days == float(java(r"STANCE_HALF_LIFE_DAYS = ([\d.]+);"))
    assert s.stance_max_age_days == int(java(r"STANCE_MAX_AGE_DAYS = (\d+);"))
    assert s.default_event_hours == int(java(r"DEFAULT_EVENT_HOURS = (\d+);"))
