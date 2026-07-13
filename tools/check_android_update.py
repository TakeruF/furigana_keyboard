#!/usr/bin/env python3
"""Verify the public Android direct-update endpoint and its APK."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass
from urllib.parse import urlsplit


MANIFEST_URL = "https://downloads.hanlu.app/latest.json"
DOWNLOAD_HOST = "downloads.hanlu.app"
DOWNLOAD_PATH_PREFIX = "/furigana-keyboard/"
MAX_MANIFEST_BYTES = 64 * 1024
SHA256 = re.compile(r"^[0-9a-f]{64}$")


class UpdateCheckError(RuntimeError):
    """A public update cannot be discovered or verified safely."""


@dataclass(frozen=True)
class UpdateManifest:
    version_code: int
    version_name: str
    download_url: str
    sha256: str
    release_notes: str | None


def _require_downloads_url(value: str, *, apk: bool) -> None:
    url = urlsplit(value)
    try:
        port = url.port
    except ValueError as error:
        raise UpdateCheckError(f"URL contains an invalid port: {value}") from error
    if url.scheme != "https" or url.hostname != DOWNLOAD_HOST:
        raise UpdateCheckError(f"URL must use https://{DOWNLOAD_HOST}: {value}")
    if url.username or url.password or port not in (None, 443) or url.fragment:
        raise UpdateCheckError(f"URL contains unsupported authority or fragment: {value}")
    if apk:
        file_name = url.path.removeprefix(DOWNLOAD_PATH_PREFIX)
        if (
            not url.path.startswith(DOWNLOAD_PATH_PREFIX)
            or not file_name
            or "/" in file_name
        ):
            raise UpdateCheckError(
                f"Download URL must use {DOWNLOAD_PATH_PREFIX}: {value}"
            )
        if not url.path.lower().endswith(".apk"):
            raise UpdateCheckError(f"Download URL does not point to an APK: {value}")


def parse_manifest(payload: bytes) -> UpdateManifest:
    if len(payload) > MAX_MANIFEST_BYTES:
        raise UpdateCheckError("latest.json is larger than 64 KiB")
    try:
        value = json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise UpdateCheckError(f"latest.json is not valid UTF-8 JSON: {error}") from error
    if not isinstance(value, dict):
        raise UpdateCheckError("latest.json must contain a JSON object")

    version_code = value.get("versionCode")
    version_name = value.get("versionName")
    download_url = value.get("downloadUrl")
    digest = value.get("sha256")
    release_notes = value.get("releaseNotes")
    if isinstance(version_code, bool) or not isinstance(version_code, int) or version_code <= 0:
        raise UpdateCheckError("versionCode must be a positive integer")
    if not isinstance(version_name, str) or not version_name.strip():
        raise UpdateCheckError("versionName must be a non-empty string")
    if not isinstance(download_url, str):
        raise UpdateCheckError("downloadUrl must be a string")
    if not isinstance(digest, str) or not SHA256.fullmatch(digest.lower()):
        raise UpdateCheckError("sha256 must be a 64-character hexadecimal digest")
    if release_notes is not None and not isinstance(release_notes, str):
        raise UpdateCheckError("releaseNotes must be a string when present")
    _require_downloads_url(download_url, apk=True)
    return UpdateManifest(
        version_code=version_code,
        version_name=version_name.strip(),
        download_url=download_url,
        sha256=digest.lower(),
        release_notes=release_notes.strip() if release_notes else None,
    )


def _open(url: str, *, accept: str, timeout: float):
    request = urllib.request.Request(
        url,
        headers={
            "Accept": accept,
            "User-Agent": "FuriganaKeyboard-release-check/1",
        },
    )
    try:
        response = urllib.request.urlopen(request, timeout=timeout)
    except urllib.error.HTTPError as error:
        raise UpdateCheckError(f"{url} returned HTTP {error.code}") from error
    except urllib.error.URLError as error:
        raise UpdateCheckError(f"Could not fetch {url}: {error.reason}") from error
    _require_downloads_url(response.geturl(), apk=url.lower().endswith(".apk"))
    return response


def fetch_manifest(*, timeout: float = 20) -> UpdateManifest:
    with _open(MANIFEST_URL, accept="application/json", timeout=timeout) as response:
        content_type = response.headers.get_content_type()
        if content_type != "application/json":
            raise UpdateCheckError(
                f"latest.json has Content-Type {content_type!r}; expected 'application/json'"
            )
        length = response.headers.get("Content-Length")
        if length is not None and int(length) > MAX_MANIFEST_BYTES:
            raise UpdateCheckError("latest.json is larger than 64 KiB")
        payload = response.read(MAX_MANIFEST_BYTES + 1)
    return parse_manifest(payload)


def verify_apk(manifest: UpdateManifest, *, timeout: float = 60) -> int:
    digest = hashlib.sha256()
    size = 0
    with _open(
        manifest.download_url,
        accept="application/vnd.android.package-archive",
        timeout=timeout,
    ) as response:
        while block := response.read(1024 * 1024):
            digest.update(block)
            size += len(block)
    actual = digest.hexdigest()
    if actual != manifest.sha256:
        raise UpdateCheckError(
            f"APK SHA-256 mismatch: latest.json={manifest.sha256}, downloaded={actual}"
        )
    return size


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Check downloads.hanlu.app/latest.json and optionally verify its APK"
    )
    parser.add_argument(
        "--current-version-code",
        type=int,
        help="Compare the published versionCode with an installed/build versionCode",
    )
    parser.add_argument(
        "--download-apk",
        action="store_true",
        help="Download the referenced APK and verify its SHA-256 digest",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    try:
        if args.current_version_code is not None and args.current_version_code <= 0:
            raise UpdateCheckError("--current-version-code must be positive")
        manifest = fetch_manifest()
        result = {
            "endpoint": MANIFEST_URL,
            "versionCode": manifest.version_code,
            "versionName": manifest.version_name,
            "downloadUrl": manifest.download_url,
            "manifest": "ok",
        }
        if args.current_version_code is not None:
            result["currentVersionCode"] = args.current_version_code
            result["updateAvailable"] = manifest.version_code > args.current_version_code
        if args.download_apk:
            result["apkBytes"] = verify_apk(manifest)
            result["apkSha256"] = "ok"
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except UpdateCheckError as error:
        print(f"Update check failed: {error}", file=sys.stderr)
        raise SystemExit(1) from error


if __name__ == "__main__":
    main()
