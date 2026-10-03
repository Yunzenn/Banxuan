"""Regression against frozen Xiaozhi auth, not an injected authentication double.

Requires aiohttp and the existing frozen checkout. A missing/mismatched checkout
fails instead of silently skipping. Override its location with XIAOZHI_FROZEN_ROOT.
No provider, network, account, production key or live server is required.
"""
import asyncio
import hashlib
import os
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[2]
COMMIT = "788f5301fdd60cc3a8ef74025bfeece9b82b94ce"
AUTH_SHA256 = "27b0af12c591c97a532ccf95ee7c28c088482ea942faf749ef8c6670ac651e90"
UPSTREAM = Path(os.environ.get("XIAOZHI_FROZEN_ROOT", REPO / ".upstream" / f"xiaozhi-esp32-server-{COMMIT}"))
AUTH_FILE = UPSTREAM / "main/xiaozhi-server/core/auth.py"
if hashlib.sha256(AUTH_FILE.read_bytes()).hexdigest() != AUTH_SHA256:
    raise RuntimeError("Auth source does not match frozen commit; refusing substitute implementation")

sys.path.insert(0, str(UPSTREAM / "main/xiaozhi-server"))
sys.path.insert(0, str(REPO / "evidence/server"))
from core.auth import AuthManager
from xiaozhi_memory_handler import MemoryAuthError, make_auth_manager_verifier

if Path(sys.modules[AuthManager.__module__].__file__).resolve() != AUTH_FILE.resolve():
    raise RuntimeError("Loaded auth is not the verified frozen source")


class MemoryRealAuthTest(unittest.TestCase):
    def setUp(self):
        # Fixed synthetic key only; never a production credential.
        self.config = {"server": {"auth_key": "banxuan-auth-test-only",
                                   "auth": {"enabled": True, "expire_seconds": 120}}}
        self.upstream = AuthManager(secret_key=self.config["server"]["auth_key"], expire_seconds=120)
        self.token = self.upstream.generate_token("client-A", "device-A")
        self.headers = {"Authorization": f"Bearer {self.token}",
                        "Client-Id": "client-A", "Device-Id": "device-A"}

    def verify(self, headers):
        return asyncio.run(make_auth_manager_verifier(self.config)(SimpleNamespace(headers=headers)))

    def test_valid_upstream_token_is_accepted_as_bound_device(self):
        self.assertIs(True, self.upstream.verify_token(self.token, "client-A", "device-A"))
        print("positive control: frozen upstream verification PASS", flush=True)
        self.assertEqual("device-A", self.verify(self.headers))
        print("same token and headers: Memory adapter PASS", flush=True)

    def test_invalid_token_is_rejected(self):
        self.assertIs(False, self.upstream.verify_token("invalid", "client-A", "device-A"))
        with self.assertRaises(MemoryAuthError):
            self.verify({**self.headers, "Authorization": "Bearer invalid"})

    def test_client_and_device_mismatch_are_rejected(self):
        for header, value in (("Client-Id", "client-B"), ("Device-Id", "device-B")):
            with self.subTest(header=header), self.assertRaises(MemoryAuthError):
                self.verify({**self.headers, header: value})

    def test_missing_or_empty_required_headers_are_rejected(self):
        for header in self.headers:
            for missing in (True, False):
                headers = dict(self.headers)
                if missing:
                    del headers[header]
                else:
                    headers[header] = ""
                with self.subTest(header=header, missing=missing), self.assertRaises(MemoryAuthError):
                    self.verify(headers)

    def test_configured_upstream_expiry_is_preserved(self):
        with patch("core.auth.time.time", return_value=1000):
            token = self.upstream.generate_token("client-A", "device-A")
        headers = {**self.headers, "Authorization": f"Bearer {token}"}
        with patch("core.auth.time.time", return_value=1100):
            self.assertIs(True, self.upstream.verify_token(token, "client-A", "device-A"))
            self.assertEqual("device-A", self.verify(headers))
        with patch("core.auth.time.time", return_value=1121):
            self.assertIs(False, self.upstream.verify_token(token, "client-A", "device-A"))
            with self.assertRaises(MemoryAuthError):
                self.verify(headers)

    def test_default_upstream_expiry_is_preserved(self):
        self.config["server"]["auth"] = {"enabled": True}
        with patch("core.auth.time.time", return_value=1000):
            token = AuthManager(secret_key=self.config["server"]["auth_key"]).generate_token("client-A", "device-A")
        with patch("core.auth.time.time", return_value=1000 + 121):
            self.assertEqual("device-A", self.verify({**self.headers, "Authorization": f"Bearer {token}"}))

    def test_existing_bare_token_form_remains_supported(self):
        self.assertEqual("device-A", self.verify({**self.headers, "Authorization": self.token}))


if __name__ == "__main__":
    print(f"frozen auth: {COMMIT}; source SHA256: {AUTH_SHA256}", flush=True)
    unittest.main(verbosity=2)
