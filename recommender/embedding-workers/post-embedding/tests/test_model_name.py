import re
from pathlib import Path

WORKERS = Path(__file__).resolve().parents[2]
FEED_CONFIG = "../../aws/lambdas/post-read/src/main/java/com/coolture/feed/FeedConfig.java"


def test_model_name_is_the_same_everywhere():
    # On another name the worker reads no rows and reports an ordinary {"upserted": 0}, and the feed finds no vectors to
    # build the viewer's from, so it ranks with affinity neutral for everyone.
    sources = {
        "post-embedding/Dockerfile": r"^ARG EMBEDDING_MODEL=(\S+)",
        "post-embedding/src/settings.py": r'^\s*embedding_model: str = "(.+)"',
        FEED_CONFIG: r'EMBEDDING_MODEL = "(.+)";',
    }
    names = {path: re.search(rx, (WORKERS / path).read_text(), re.M).group(1) for path, rx in sources.items()}
    assert len(set(names.values())) == 1, names
