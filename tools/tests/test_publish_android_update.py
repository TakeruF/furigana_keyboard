import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "publish_android_update.py"
SPEC = importlib.util.spec_from_file_location("publish_android_update", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class PublishAndroidUpdateTest(unittest.TestCase):
    def test_reads_version_for_the_exact_gradle_output(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            apk = directory / "app-direct-release.apk"
            apk.write_bytes(b"apk")
            (directory / "output-metadata.json").write_text(
                json.dumps(
                    {
                        "elements": [
                            {
                                "outputFile": apk.name,
                                "versionCode": 7,
                                "versionName": "1.0.0-beta.7",
                            }
                        ]
                    }
                ),
                encoding="utf-8",
            )

            self.assertEqual(
                MODULE.read_built_apk_version(apk),
                (7, "1.0.0-beta.7"),
            )

    def test_rejects_apk_without_matching_gradle_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / "copied.apk"
            apk.write_bytes(b"apk")

            with self.assertRaises(SystemExit):
                MODULE.read_built_apk_version(apk)

    def test_generates_versioned_apk_and_latest_manifest_only(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            apk = directory / "build" / "app-direct-release.apk"
            apk.parent.mkdir()
            apk.write_bytes(b"signed apk fixture")
            (apk.parent / "output-metadata.json").write_text(
                json.dumps(
                    {
                        "elements": [
                            {
                                "outputFile": apk.name,
                                "versionCode": 12,
                                "versionName": "1.2.0",
                            }
                        ]
                    }
                ),
                encoding="utf-8",
            )
            output = directory / "dist"

            with patch.object(
                sys,
                "argv",
                [
                    str(SCRIPT),
                    "--apk",
                    str(apk),
                    "--version-code",
                    "12",
                    "--version-name",
                    "1.2.0",
                    "--output",
                    str(output),
                ],
            ):
                MODULE.main()

            self.assertEqual(
                {path.name for path in output.iterdir()},
                {"1.2.0.apk", "latest.json"},
            )
            manifest = json.loads((output / "latest.json").read_text(encoding="utf-8"))
            self.assertEqual(manifest["versionCode"], 12)
            self.assertEqual(manifest["versionName"], "1.2.0")
            self.assertEqual(
                manifest["downloadUrl"],
                "https://downloads.hanlu.app/1.2.0.apk",
            )
            self.assertNotIn("latest.apk", manifest["downloadUrl"])


if __name__ == "__main__":
    unittest.main()
