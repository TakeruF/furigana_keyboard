#!/usr/bin/env python3
"""Build and sign a static reading-database update release.

The output directory can be uploaded to any HTTPS object storage. The apps
verify the detached ECDSA P-256 signature before trusting the manifest, then
verify the database size, SHA-256 digest, SQLite integrity and schema version.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import shutil
import sqlite3
import subprocess
from pathlib import Path


SUPPORTED_SCHEMA_VERSION = 7


def database_metadata(path: Path) -> dict[str, str]:
    with sqlite3.connect(f"file:{path}?mode=ro", uri=True) as db:
        integrity = db.execute("PRAGMA integrity_check").fetchone()
        if integrity != ("ok",):
            raise ValueError(f"SQLite integrity check failed: {integrity!r}")
        return dict(db.execute("SELECT key, value FROM metadata"))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--database", type=Path, required=True)
    parser.add_argument("--version", type=int, required=True)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--private-key", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("reading-update-dist"))
    parser.add_argument("--min-app-version", type=int, default=1)
    args = parser.parse_args()

    metadata = database_metadata(args.database)
    schema_version = int(metadata["schema_version"])
    if schema_version != SUPPORTED_SCHEMA_VERSION:
        raise ValueError(
            f"Expected schema {SUPPORTED_SCHEMA_VERSION}, found {schema_version}"
        )
    if args.version <= 0 or args.min_app_version <= 0:
        raise ValueError("Versions must be positive integers")
    if not args.database_url.startswith("https://"):
        raise ValueError("Database URL must use HTTPS")

    payload = args.database.read_bytes()
    args.output.mkdir(parents=True, exist_ok=True)
    database_name = f"reading-{args.version}.db"
    shutil.copyfile(args.database, args.output / database_name)
    manifest = {
        "formatVersion": 1,
        "dataVersion": args.version,
        "schemaVersion": schema_version,
        "minAppVersion": args.min_app_version,
        "databaseUrl": args.database_url,
        "databaseSize": len(payload),
        "databaseSha256": hashlib.sha256(payload).hexdigest(),
        "dictionaryDate": metadata.get("kanjidic_date", ""),
    }
    manifest_path = args.output / "manifest.json"
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        + "\n",
        encoding="utf-8",
    )
    signature_path = args.output / "manifest.json.sig"
    subprocess.run(
        [
            "openssl", "dgst", "-sha256", "-sign", str(args.private_key),
            "-out", str(signature_path), str(manifest_path),
        ],
        check=True,
    )
    signature_path.write_text(
        base64.b64encode(signature_path.read_bytes()).decode("ascii") + "\n",
        encoding="ascii",
    )
    print(f"Wrote {args.output} (data version {args.version})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
