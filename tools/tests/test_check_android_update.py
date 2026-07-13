import importlib.util
import json
import sys
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "check_android_update.py"
SPEC = importlib.util.spec_from_file_location("check_android_update", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class CheckAndroidUpdateTest(unittest.TestCase):
    def manifest(self, **overrides: object):
        value = {
            "versionCode": 12,
            "versionName": "1.2.0",
            "downloadUrl": "https://downloads.hanlu.app/furigana-keyboard/1.2.0.apk",
            "sha256": "a" * 64,
        }
        value.update(overrides)
        return MODULE.parse_manifest(json.dumps(value).encode())

    def test_parses_published_manifest_contract(self) -> None:
        manifest = self.manifest(releaseNotes=" Recognition improvements ")

        self.assertEqual(manifest.version_code, 12)
        self.assertEqual(manifest.version_name, "1.2.0")
        self.assertEqual(manifest.release_notes, "Recognition improvements")

    def test_rejects_boolean_version_code(self) -> None:
        with self.assertRaises(MODULE.UpdateCheckError):
            self.manifest(versionCode=True)

    def test_rejects_download_on_another_host(self) -> None:
        with self.assertRaises(MODULE.UpdateCheckError):
            self.manifest(downloadUrl="https://example.com/1.2.0.apk")

    def test_rejects_download_with_invalid_port(self) -> None:
        with self.assertRaises(MODULE.UpdateCheckError):
            self.manifest(downloadUrl="https://downloads.hanlu.app:invalid/1.2.0.apk")

    def test_rejects_apk_outside_distribution_directory(self) -> None:
        with self.assertRaises(MODULE.UpdateCheckError):
            self.manifest(downloadUrl="https://downloads.hanlu.app/1.2.0.apk")

    def test_rejects_oversized_manifest(self) -> None:
        with self.assertRaises(MODULE.UpdateCheckError):
            MODULE.parse_manifest(b" " * (MODULE.MAX_MANIFEST_BYTES + 1))


if __name__ == "__main__":
    unittest.main()
