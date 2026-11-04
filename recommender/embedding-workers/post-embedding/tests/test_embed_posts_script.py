"""Runs scripts/embed-posts.sh against a stand-in for `docker`, which is the only way it reaches the database."""

import os
import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[4]
SCRIPT = "scripts/embed-posts.sh"
# The full path, because on Windows a bare "bash" runs WSL's from System32, whatever PATH says.
BASH = shutil.which("bash")

pytestmark = pytest.mark.skipif(BASH is None, reason="needs bash")


def run_script(tmp_path, docker_body):
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    docker = bin_dir / "docker"
    docker.write_text(f"#!/usr/bin/env bash\n{docker_body}\n", newline="\n")
    docker.chmod(0o755)
    env = {**os.environ, "PATH": f"{bin_dir}{os.pathsep}{os.environ['PATH']}", "DRY_RUN": "1"}
    return subprocess.run([BASH, SCRIPT], cwd=ROOT, env=env, capture_output=True, text=True)


def test_a_failed_query_fails_the_script(tmp_path):
    result = run_script(tmp_path, 'echo \'service "postgres" is not running\' >&2; exit 1')

    assert result.returncode != 0
    assert "No posts need embedding" not in result.stdout
    assert "postgres" in result.stderr


def test_no_posts_is_still_a_success(tmp_path):
    result = run_script(tmp_path, "exit 0")

    assert result.returncode == 0
    assert "No posts need embedding" in result.stdout


def test_each_returned_post_is_embedded(tmp_path):
    result = run_script(tmp_path, "echo post-1; echo post-2")

    assert result.returncode == 0
    assert "Embedding 2 post(s)" in result.stdout
    assert "posts/post-1" in result.stdout
    assert "posts/post-2" in result.stdout
