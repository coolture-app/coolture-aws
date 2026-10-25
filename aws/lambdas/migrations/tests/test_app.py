import contextlib
import hashlib
import json
from types import SimpleNamespace

import pytest

import app


class FakeConnection:
    def __init__(self, applied: dict[int, str]):
        self.applied = applied
        self.statements: list[tuple[str, tuple | None]] = []

    def execute(self, sql: str, params: tuple | None = None):
        self.statements.append((" ".join(sql.split()), params))
        return SimpleNamespace(fetchall=lambda: list(self.applied.items()))

    def transaction(self):
        return contextlib.nullcontext()


def migration(version: int, sql: str) -> app.Migration:
    return app.Migration(version, f"V{version}__m.sql", sql, hashlib.sha256(sql.encode()).hexdigest())


def cfn_event(request_type: str) -> dict:
    return {
        "RequestType": request_type,
        "ResponseURL": "https://cloudformation-custom-resource-response.example/abc",
        "StackId": "arn:aws:cloudformation:eu-north-1:123:stack/coolture-backend-dev/x",
        "RequestId": "req-1",
        "LogicalResourceId": "DatabaseMigrations",
    }


@pytest.fixture
def sent(monkeypatch):
    responses = []
    monkeypatch.setattr(app, "send_response", lambda event, status, reason, data: responses.append((status, reason, data)))
    return responses


def test_should_load_migrations_ordered_by_version_when_directory_contains_sql_files(tmp_path):
    # given
    (tmp_path / "V10__ten.sql").write_text("SELECT 10;")
    (tmp_path / "V2__two.sql").write_text("SELECT 2;")
    (tmp_path / "V1__one.sql").write_text("SELECT 1;")
    (tmp_path / "README.md").write_text("not a migration")

    # when
    result = app.load_migrations(tmp_path)

    # then
    assert result == [
        app.Migration(1, "V1__one.sql", "SELECT 1;", hashlib.sha256(b"SELECT 1;").hexdigest()),
        app.Migration(2, "V2__two.sql", "SELECT 2;", hashlib.sha256(b"SELECT 2;").hexdigest()),
        app.Migration(10, "V10__ten.sql", "SELECT 10;", hashlib.sha256(b"SELECT 10;").hexdigest()),
    ]


def test_should_fail_when_two_migrations_share_a_version(tmp_path):
    # given
    (tmp_path / "V1__one.sql").write_text("SELECT 1;")
    (tmp_path / "V1__other.sql").write_text("SELECT 1;")

    # when
    with pytest.raises(app.MigrationError) as error:
        app.load_migrations(tmp_path)

    # then
    assert "duplicate migration version V1" in str(error.value)


def test_should_ship_repository_migrations_when_packaged():
    # given
    expected = [
        "V1__initial_schema.sql",
        "V2__drop_event_categories.sql",
        "V3__increase_posts_title.sql",
        "V4__add_pgvector_extension.sql",
        "V5__rebuild_embedding_columns.sql",
        "V6__add_embedding_model_column.sql",
    ]

    # when
    result = [m.name for m in app.load_migrations()]

    # then
    assert result[: len(expected)] == expected


def test_should_apply_only_pending_migrations_when_some_are_already_applied():
    # given
    first, second, third = migration(1, "SELECT 1;"), migration(2, "SELECT 2;"), migration(3, "SELECT 3;")
    conn = FakeConnection({1: first.checksum})

    # when
    result = app.migrate(conn, [first, second, third])

    # then
    assert (result, conn.statements) == (
        ["V2__m.sql", "V3__m.sql"],
        [
            (" ".join(app.CREATE_HISTORY_TABLE.split()), None),
            ("SELECT pg_advisory_lock(%s)", (app.ADVISORY_LOCK_ID,)),
            ("SELECT version, checksum FROM schema_migrations", None),
            ("SELECT 2;", None),
            ("INSERT INTO schema_migrations (version, name, checksum) VALUES (%s, %s, %s)", (2, "V2__m.sql", second.checksum)),
            ("SELECT 3;", None),
            ("INSERT INTO schema_migrations (version, name, checksum) VALUES (%s, %s, %s)", (3, "V3__m.sql", third.checksum)),
            ("SELECT pg_advisory_unlock(%s)", (app.ADVISORY_LOCK_ID,)),
        ],
    )


def test_should_fail_and_release_lock_when_applied_migration_was_edited():
    # given
    conn = FakeConnection({1: "checksum-of-the-original-file"})

    # when
    with pytest.raises(app.MigrationError) as error:
        app.migrate(conn, [migration(1, "SELECT 'edited';")])

    # then
    assert (str(error.value), conn.statements[-1]) == (
        "V1__m.sql was changed after it was applied - add a new migration instead",
        ("SELECT pg_advisory_unlock(%s)", (app.ADVISORY_LOCK_ID,)),
    )


def test_should_report_success_with_applied_migrations_when_stack_is_created(monkeypatch, sent):
    # given
    monkeypatch.setattr(app, "run_migrations", lambda: ["V1__initial_schema.sql", "V2__drop_event_categories.sql"])

    # when
    result = app.lambda_handler(cfn_event("Create"), None)

    # then
    assert (result, sent) == (
        {"status": "SUCCESS"},
        [("SUCCESS", "", {"Applied": "V1__initial_schema.sql,V2__drop_event_categories.sql"})],
    )


def test_should_report_failure_when_migration_raises_on_update(monkeypatch, sent):
    # given
    def failing():
        raise app.MigrationError("boom")

    monkeypatch.setattr(app, "run_migrations", failing)

    # when
    result = app.lambda_handler(cfn_event("Update"), None)

    # then
    assert (result, sent) == ({"status": "FAILED"}, [("FAILED", "MigrationError: boom", {})])


def test_should_skip_migrations_when_stack_is_deleted(monkeypatch, sent):
    # given
    monkeypatch.setattr(app, "run_migrations", lambda: pytest.fail("migrations must not run on delete"))

    # when
    result = app.lambda_handler(cfn_event("Delete"), None)

    # then
    assert (result, sent) == ({"status": "SUCCESS"}, [("SUCCESS", "", {})])


def test_should_return_applied_migrations_when_invoked_manually(monkeypatch):
    # given
    monkeypatch.setattr(app, "run_migrations", lambda: ["V7__new.sql"])

    # when
    result = app.lambda_handler({}, None)

    # then
    assert result == {"applied": ["V7__new.sql"]}


def test_should_put_cloudformation_response_when_sending_result(monkeypatch):
    # given
    captured = {}

    @contextlib.contextmanager
    def fake_urlopen(request, timeout):
        captured.update(url=request.full_url, method=request.get_method(), body=json.loads(request.data))
        yield SimpleNamespace(status=200)

    monkeypatch.setattr(app.urllib.request, "urlopen", fake_urlopen)

    # when
    app.send_response(cfn_event("Create"), "SUCCESS", "", {"Applied": ""})

    # then
    assert captured == {
        "url": "https://cloudformation-custom-resource-response.example/abc",
        "method": "PUT",
        "body": {
            "Status": "SUCCESS",
            "Reason": "",
            "PhysicalResourceId": "coolture-db-migrations",
            "StackId": "arn:aws:cloudformation:eu-north-1:123:stack/coolture-backend-dev/x",
            "RequestId": "req-1",
            "LogicalResourceId": "DatabaseMigrations",
            "Data": {"Applied": ""},
        },
    }
