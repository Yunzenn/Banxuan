import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("prepare_preview", Path(__file__).resolve().parents[2] / "scripts/prepare_preview.py")
preview = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preview)


class PreviewPayloadTest(unittest.TestCase):
    baseline = ["AndroidManifest.xml", "classes.dex", "assets/licenses/concentus-LICENSE.txt"]

    def test_minimal_payload(self):
        preview.check_contents(self.baseline + ["lib/arm64-v8a/libdatastore_shared_counter.so"])

    def test_missing_license(self):
        with self.assertRaises(ValueError):
            preview.check_contents(self.baseline[:2])

    def test_proprietary_customer_and_secret_payloads_fail(self):
        for path in ["lib/arm64-v8a/libLive2DCubismCoreJNI.so", "assets/character/portrait.png",
                     "assets/model/Haru.moc3", "private/signing.jks", "recording.mp4"]:
            with self.subTest(path=path), self.assertRaises(ValueError):
                preview.check_contents(self.baseline + [path])


if __name__ == "__main__":
    unittest.main()
