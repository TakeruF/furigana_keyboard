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


def read_built_apk_version(apk: Path) -> tuple[int, str]:
    """Read the version Gradle recorded for this exact APK output."""
    metadata_path = apk.parent / "output-metadata.json"
    if not metadata_path.is_file():
        raise SystemExit(
            f"Gradle output metadata not found: {metadata_path}. "
            "Use the APK directly from app/build/outputs/apk/direct/release."
        )
    try:
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        element = next(
            item
            for item in metadata["elements"]
            if item.get("outputFile") == apk.name
        )
        return int(element["versionCode"]), str(element["versionName"])
    except (KeyError, TypeError, ValueError, StopIteration, json.JSONDecodeError) as error:
        raise SystemExit(
            f"Could not read version for {apk.name} from {metadata_path}: {error}"
        ) from error


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
    built_version_code, built_version_name = read_built_apk_version(args.apk)
    if (built_version_code, built_version_name) != (
        args.version_code,
        args.version_name,
    ):
        raise SystemExit(
            "Published version does not match the built APK: "
            f"APK is {built_version_name} ({built_version_code}), arguments are "
            f"{args.version_name} ({args.version_code})"
        )

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
