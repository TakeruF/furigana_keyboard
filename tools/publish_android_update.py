#!/usr/bin/env python3
"""Prepare a directly distributed Android APK and its latest.json manifest."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
from pathlib import Path


SAFE_FILE_COMPONENT = re.compile(r"^[A-Za-z0-9._-]+$")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--release-notes")
    parser.add_argument(
        "--base-url",
        default="https://downloads.hanlu.app",
        help="HTTPS origin where the generated files will be uploaded",
    )
    parser.add_argument("--output", type=Path, default=Path("android-update-dist"))
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if not args.apk.is_file():
        raise SystemExit(f"APK not found: {args.apk}")
    if args.version_code <= 0:
        raise SystemExit("--version-code must be positive")
    if not SAFE_FILE_COMPONENT.fullmatch(args.version_name):
        raise SystemExit("--version-name may contain only letters, digits, '.', '_' and '-'")
    if args.base_url.rstrip("/") != "https://downloads.hanlu.app":
        raise SystemExit("--base-url must be https://downloads.hanlu.app")

    args.output.mkdir(parents=True, exist_ok=True)
    apk_name = f"{args.version_name}.apk"
    output_apk = args.output / apk_name
    shutil.copy2(args.apk, output_apk)
    digest = hashlib.sha256()
    with output_apk.open("rb") as apk_file:
        for block in iter(lambda: apk_file.read(1024 * 1024), b""):
            digest.update(block)
    sha256 = digest.hexdigest()

    manifest: dict[str, object] = {
        "versionCode": args.version_code,
        "versionName": args.version_name,
        "downloadUrl": f"https://downloads.hanlu.app/{apk_name}",
        "sha256": sha256,
    }
    if args.release_notes:
        manifest["releaseNotes"] = args.release_notes
    (args.output / "latest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"Prepared {output_apk} and {args.output / 'latest.json'}")


if __name__ == "__main__":
    main()
