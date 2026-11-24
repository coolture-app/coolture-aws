#!/bin/sh
# coolture-scraper.service runs this from the repository's root, after checking out $SCRAPER_BRANCH.
set -eu

venv="$STATE_DIRECTORY/venv"
# The venv keeps a copy of the requirements it was installed from, so a change to them installs again.
if ! cmp -s scraper/requirements.txt "$venv/requirements.txt"; then
    "$venv/bin/pip" install --quiet --disable-pip-version-check -r scraper/requirements.txt
    cp scraper/requirements.txt "$venv/requirements.txt"
fi
exec "$venv/bin/python" scraper/remote.py
