"""Contract and wiring guards; not a claim of hosted signing or device upgrade PASS."""
from copy import deepcopy
import hashlib
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from prepare_preview import validate_apk_contract
from release_contract import check_tag, pinned_certificate, verify_metadata, verify_remote_release


class ReleaseContractTest(unittest.TestCase):
    commit = "a" * 40
    cert = "b" * 64
    digest = "c" * 64
    tag = "v0.5.0-preview.1"
    name = "0.5.0-preview.1"

    def tag_check(self, **overrides):
        values = dict(tag=self.tag, name=self.name, code=6, commit=self.commit,
                      head=self.commit, tag_commit=self.commit, previous_codes=[4, 5])
        values.update(overrides)
        check_tag(**values)

    def test_tag_commit_version_chain(self):
        self.tag_check()

    def test_wrong_tag_head_event_and_nonmonotonic_code(self):
        for changes in ({"tag": "v0.4.1-preview"}, {"head": "d" * 40},
                        {"tag_commit": "d" * 40}, {"commit": "not-a-sha"}, {"code": 5}, {"code": 4}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.tag_check(**changes)

    def test_certificate_must_be_explicitly_configured(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "fingerprint"
            for invalid in ("UNCONFIGURED", "", "b" * 63, "g" * 64):
                path.write_text(invalid)
                with self.assertRaises(ValueError):
                    pinned_certificate(path)
            path.write_text(self.cert.upper() + "\n")
            self.assertEqual(self.cert, pinned_certificate(path))

    def test_real_identity_fields_not_substring_matches(self):
        badge = "package: name='com.aiwatch.probe' versionCode='6' versionName='0.5.0-preview.1'\nsdkVersion:'28'\n"
        sig = f"Signer #1 certificate SHA-256 digest: {self.cert}\n"
        self.assertEqual(self.cert, validate_apk_contract(badge, sig, self.name, 6, self.cert))
        for bad in (badge.replace("versionCode='6'", "versionCode='60'"),
                    badge.replace("com.aiwatch.probe", "com.aiwatch.probe.fake"),
                    badge.replace("sdkVersion:'28'", "sdkVersion:'29'"),
                    badge + "application-debuggable\n"):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                validate_apk_contract(bad, sig, self.name, 6, self.cert)
        for bad_sig in ("", sig.replace(self.cert, "d" * 64), sig + sig):
            with self.assertRaises(ValueError):
                validate_apk_contract(badge, bad_sig, self.name, 6, self.cert)

    def test_every_metadata_field_is_bound(self):
        info = dict(tag=self.tag, commit=self.commit, versionName=self.name, versionCode=6,
                    apk=f"banxuan-{self.name}.apk", sha256=self.digest, minSdk=28,
                    certificateSha256=self.cert, signing="Pinned release certificate")
        def validate(data):
            verify_metadata(data, self.tag, self.commit, self.name, 6, self.digest, self.cert)
        validate(info)
        for key in info:
            bad = dict(info, **{key: "tampered"})
            with self.subTest(field=key), self.assertRaises(ValueError):
                validate(bad)

    def test_uploaded_draft_inventory_digest_and_state(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "test.apk").write_bytes(b"fixture-not-an-apk")
            remote = dict(tag_name=self.tag, draft=True, prerelease=True, assets=[
                dict(name="test.apk", size=18, state="uploaded",
                     digest="sha256:" + hashlib.sha256(b"fixture-not-an-apk").hexdigest())])
            verify_remote_release(remote, self.tag, root)
            for key, value in (("state", "new"), ("size", 0), ("digest", None), ("name", "wrong.apk")):
                bad = deepcopy(remote)
                bad["assets"][0][key] = value
                with self.subTest(key=key), self.assertRaises(ValueError):
                    verify_remote_release(bad, self.tag, root)
            for changes in ({"draft": False}, {"tag_name": "wrong"}, {"prerelease": False}, {"assets": []}):
                with self.assertRaises(ValueError):
                    verify_remote_release(dict(remote, **changes), self.tag, root)

    def test_credentials_are_not_referenced_in_regular_ci(self):
        ci = (ROOT / ".github/workflows/ci.yml").read_text()
        self.assertNotIn("secrets.", ci)
        self.assertNotIn("environment:", ci)
        self.assertNotIn("secrets: inherit", ci)
        release = (ROOT / ".github/workflows/release.yml").read_text()
        self.assertNotIn("pull_request", release)
        self.assertNotIn("workflow_dispatch", release)
        sign = release.split("  sign:\n", 1)[1].split("  publish:\n", 1)[0]
        self.assertIn("environment: release", sign)
        self.assertNotIn("./gradlew", sign)
        for name in ("BANXUAN_KEYSTORE_BASE64", "BANXUAN_KEYSTORE_PASSWORD", "BANXUAN_KEY_ALIAS", "BANXUAN_KEY_PASSWORD"):
            self.assertEqual(1, release.count("secrets." + name))
            self.assertIn("secrets." + name, sign)
        self.assertIn("uses: ./.github/workflows/ci.yml", release)
        self.assertNotIn("--clobber", release)


if __name__ == "__main__":
    unittest.main()
