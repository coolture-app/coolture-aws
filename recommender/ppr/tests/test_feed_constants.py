import re
from pathlib import Path

import app

FEED = Path(__file__).resolve().parents[3] / "aws/lambdas/post-read/src/main/java/com/coolture"
FEED_CONFIG = (FEED / "feed/FeedConfig.java").read_text()


def java(pattern: str) -> str:
    return re.search(pattern, FEED_CONFIG).group(1)


def test_the_job_reads_stances_and_events_as_the_feed_does():
    # A difference would score users from other stances or other ages than the vector the feed builds from them.
    assert app.EMBEDDING_MODEL == java(r'EMBEDDING_MODEL = "(.+)";')
    weights = re.findall(r'"(\w+)", ([\d.]+)', java(r"STANCE_WEIGHTS = Map\.of\(([^)]*)\)"))
    assert app.STANCE_WEIGHTS == {k: float(v) for k, v in weights}
    assert app.STANCE_HALF_LIFE_DAYS == float(java(r"STANCE_HALF_LIFE_DAYS = ([\d.]+);"))
    assert app.STANCE_MAX_AGE_DAYS == int(java(r"STANCE_MAX_AGE_DAYS = (\d+);"))
    assert app.DEFAULT_EVENT_HOURS == int(java(r"DEFAULT_EVENT_HOURS = (\d+);"))
    before, after = re.search(r'EVENT_END =\s*"(.+?)" \+ FeedConfig\.DEFAULT_EVENT_HOURS \+ "(.+?)"',
                              (FEED / "repository/PostSql.java").read_text()).groups()
    assert app.EVENT_END == f"{before}{app.DEFAULT_EVENT_HOURS}{after}"
