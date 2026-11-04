import re
from pathlib import Path

WORKERS = Path(__file__).resolve().parents[2]
SETTING = r'^\s*embedding_model: str = "(.+)"'


def test_model_name_is_the_same_everywhere():
    # A worker on another name reads no rows, and reports it as an ordinary {"upserted": 0}.
    sources = {
        "post-embedding/Dockerfile": r"^ARG EMBEDDING_MODEL=(\S+)",
        "post-embedding/src/settings.py": SETTING,
        "user-embedding/src/settings.py": SETTING,
    }
    names = {path: re.search(rx, (WORKERS / path).read_text(), re.M).group(1) for path, rx in sources.items()}
    assert len(set(names.values())) == 1, names
