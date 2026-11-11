import hashlib
import json
import logging
import os
import re
import time
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import psycopg

logger = logging.getLogger("db-migrations")
logger.setLevel(logging.INFO)

MIGRATIONS_DIR = Path(os.environ.get("MIGRATIONS_DIR", Path(__file__).resolve().parent / "sql"))
MIGRATION_FILE = re.compile(r"^V(\d+)__(.+)\.sql$")
PHYSICAL_RESOURCE_ID = "coolture-db-migrations"
ADVISORY_LOCK_ID = 7_300_001

CREATE_HISTORY_TABLE = """
    CREATE TABLE IF NOT EXISTS schema_migrations (
        version    INT PRIMARY KEY,
        name       VARCHAR(255) NOT NULL,
        checksum   VARCHAR(64)  NOT NULL,
        applied_at TIMESTAMPTZ  NOT NULL DEFAULT now()
    )
"""


@dataclass(frozen=True)
class Migration:
    version: int
    name: str
    sql: str
    checksum: str


class MigrationError(Exception):
    pass


def load_migrations(directory: Path = MIGRATIONS_DIR) -> list[Migration]:
    migrations: dict[int, Migration] = {}
    for path in directory.iterdir():
        match = MIGRATION_FILE.match(path.name)
        if not match:
            continue
        version = int(match.group(1))
        if version in migrations:
            raise MigrationError(f"duplicate migration version V{version}: {migrations[version].name}, {path.name}")
        sql = path.read_text(encoding="utf-8")
        migrations[version] = Migration(version, path.name, sql, hashlib.sha256(sql.encode()).hexdigest())
    return [migrations[v] for v in sorted(migrations)]


def migrate(conn: Any, migrations: list[Migration]) -> list[str]:
    conn.execute(CREATE_HISTORY_TABLE)
    conn.execute("SELECT pg_advisory_lock(%s)", (ADVISORY_LOCK_ID,))
    try:
        applied = dict(conn.execute("SELECT version, checksum FROM schema_migrations").fetchall())
        newly_applied = []
        for migration in migrations:
            if migration.version in applied:
                if applied[migration.version] != migration.checksum:
                    raise MigrationError(f"{migration.name} was changed after it was applied - add a new migration instead")
                continue
            logger.info("applying %s", migration.name)
            with conn.transaction():
                conn.execute(migration.sql)
                conn.execute(
                    "INSERT INTO schema_migrations (version, name, checksum) VALUES (%s, %s, %s)",
                    (migration.version, migration.name, migration.checksum),
                )
            newly_applied.append(migration.name)
        return newly_applied
    finally:
        conn.execute("SELECT pg_advisory_unlock(%s)", (ADVISORY_LOCK_ID,))


def connect(attempts: int = 5, delay_seconds: float = 10) -> psycopg.Connection:
    for attempt in range(1, attempts + 1):
        try:
            return psycopg.connect(
                host=os.environ["DB_HOST"],
                port=int(os.environ.get("DB_PORT", "5432")),
                dbname=os.environ["DB_NAME"],
                user=os.environ["DB_USERNAME"],
                password=os.environ["DB_PASSWORD"],
                connect_timeout=int(os.environ.get("DB_CONNECT_TIMEOUT_SECONDS", "30")),
                autocommit=True,
            )
        except psycopg.OperationalError as e:
            if attempt == attempts:
                raise
            logger.warning("database not reachable yet (attempt %d/%d): %s", attempt, attempts, e)
            time.sleep(delay_seconds)
    raise MigrationError("unreachable")


def run_migrations() -> list[str]:
    migrations = load_migrations()
    with connect() as conn:
        applied = migrate(conn, migrations)
    logger.info("database is at V%s, applied now: %s", migrations[-1].version if migrations else 0, applied or "nothing")
    return applied


def send_response(event: dict[str, Any], status: str, reason: str, data: dict[str, str]) -> None:
    body = json.dumps({
        "Status": status,
        "Reason": reason[:3000],
        "PhysicalResourceId": event.get("PhysicalResourceId") or PHYSICAL_RESOURCE_ID,
        "StackId": event["StackId"],
        "RequestId": event["RequestId"],
        "LogicalResourceId": event["LogicalResourceId"],
        "Data": data,
    }).encode()
    request = urllib.request.Request(
        event["ResponseURL"],
        data=body,
        method="PUT",
        headers={"Content-Type": "", "Content-Length": str(len(body))},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        logger.info("CloudFormation response sent: %s %s", status, response.status)


def lambda_handler(event: dict[str, Any], _context: Any) -> dict[str, Any]:
    if "ResponseURL" not in event:
        return {"applied": run_migrations()}

    status, reason, data = "SUCCESS", "", {}
    try:
        if event["RequestType"] in ("Create", "Update"):
            data = {"Applied": ",".join(run_migrations())}
    except Exception as e:
        logger.exception("migration failed")
        status, reason = "FAILED", f"{type(e).__name__}: {e}"
    send_response(event, status, reason, data)
    return {"status": status}
