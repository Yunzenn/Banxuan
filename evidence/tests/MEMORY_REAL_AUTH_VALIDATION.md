# Memory real-auth regression — 2026-10-03

Status: **PASS** (local frozen-source integration). Deployment / cloud E2E: **PENDING**.

The adapter passed a whole config dict to `AuthManager(secret_key, expire_seconds)`.
The frozen `_sign()` expects `secret_key.encode()`, so verification swallowed the type error
and returned False for a valid token. It also treated the bool verification result as a payload.

Reuse source: `xinnan-tech/xiaozhi-esp32-server@788f5301fdd60cc3a8ef74025bfeece9b82b94ce`, MIT.
No upstream source is copied or modified by this PR. The test loads its actual `core/auth.py`,
checks SHA256 `27b0af12c591c97a532ccf95ee7c28c088482ea942faf749ef8c6670ac651e90`,
and fails if the checkout is missing or differs. It also checks the loaded module path.
Frozen source locations, relative to `main/xiaozhi-server`:

- `core/auth.py::AuthManager.__init__ / generate_token / verify_token`: HMAC over client_id,
  device_id and timestamp; verification returns bool. Default lifetime is 30 days.
- `core/api/ota_handler.py::OTAHandler.__init__`: resolved `server.auth_key` and optional
  `server.auth.expire_seconds` supply the manager.
- `core/websocket_server.py::WebSocketServer.__init__`: same construction.
- `app.py::main`: resolves the startup secret from server config, manager secret or generated fallback.

The fix uses that resolved startup configuration and the same expiry semantics. On true verification,
the presented device ID is cryptographically bound to this token and becomes the memory subject.
No JWT payload, refresh token or new protocol is introduced. No allowed-device auth bypass is added.

## Before / after

Exact command in repository root:

```sh
python evidence/tests/test_memory_real_auth.py
```

Dependencies in the tested environment: Python 3.13.11 and aiohttp 3.13.4. The aiohttp handler is imported
normally; only the HTTP Request headers are supplied as an object fixture. The upstream AuthManager,
its token generation/verification and the Memory adapter are real. This does not start a live server.
Set `XIAOZHI_FROZEN_ROOT` to an existing checkout root if it is outside the default `.upstream` path.

BEFORE: upstream positive control PASS; Memory adapter REJECT; 7 tests, 4 errors.
Raw output: [MEMORY_REAL_AUTH_BEFORE.log](MEMORY_REAL_AUTH_BEFORE.log).

AFTER: upstream positive control PASS; same token + headers Memory adapter PASS; **7/7**, no skips.
Raw output: [MEMORY_REAL_AUTH_AFTER.log](MEMORY_REAL_AUTH_AFTER.log).

Cases: valid token, invalid token, client/device mismatch, missing/empty headers,
configured expiry, default expiry, existing bare-token acceptance. Expiry uses the upstream clock
with synthetic timestamps; no network or paid service is invoked. All keys are test-only literals.

Existing regression commands and results:

```text
python evidence/tests/test_memory_http_boundary.py          7/7 PASS
python evidence/tests/test_memory_http_handler.py          10/10 PASS
python evidence/tests/test_canonical_memory_conformance.py 33/33 PASS
```

Android/UI/Room/release workflows/canonical rules/cloud/upstream unchanged. The startup key must be
resolved before wiring the handler, as for the existing WebSocket/OTA services. Public bootstrap
admission and production credential ownership remain separate pending work.
