"""The aiohttp binding for the canonical memory surface.

Thin on purpose. All the protocol - routing, payload decoding, error codes, and above all the rule that
the subject comes from the authenticated identity - lives in `canonical_memory_http.py`, which imports
nothing from aiohttp and therefore runs in tests without a server environment. This file only translates
a `web.Request` into a `MemoryHttpRequest` and back, and mounts onto the frozen server's existing
`SimpleHttpServer` application rather than starting a second one.

Not mounted by default. Registration is deliberately a separate step so that enabling the memory surface
is a decision someone makes, not something that happens because the module was imported.

Reuse note: the server is the frozen `xinnan-tech/xiaozhi-esp32-server@788f530...` `SimpleHttpServer`,
which is already an `aiohttp.web.Application` carrying the OTA and Vision routes. No second framework,
no separate microservice.
"""

from __future__ import annotations

from typing import Any, Awaitable, Callable, Dict, List, Optional

from aiohttp import web

from canonical_memory_http import (
    BASE_PATH,
    CanonicalMemoryHttp,
    MemoryHttpRequest,
    error_response,
)


class MemoryAuthError(Exception):
    """The request could not be attributed to a subject.

    Raised by a verifier. Never raised to mean "this is a test client": a bypass branch in a production
    handler is a bypass branch that ships, so tests inject a verifier instead.
    """


def make_auth_manager_verifier(config: dict) -> Callable[[web.Request], Awaitable[str]]:
    """Build a verifier from the frozen server's own ``AuthManager``.

    The OTA route authenticates with ``AuthManager``'s client_id + device_id + HMAC token, and the
    device already holds that token from bootstrap, so the memory surface reuses it. The Vision route
    uses a *different* mechanism (``core.utils.auth`` JWT/AES, including a web-test-client special
    case); copying that here would have been a plausible-looking mistake.

    The import is inside the function because it needs the server's own configuration and package layout,
    which this module must not require merely to be importable.

    Returns the authenticated **device id**, which becomes the subject. Nothing in the request body or
    query can influence it.
    """

    def verifier_factory() -> Callable[[web.Request], Awaitable[str]]:  # pragma: no cover - server env
        from core.auth import AuthManager  # type: ignore

        async def verify(request: web.Request) -> str:
            client_id = request.headers.get("Client-Id", "")
            device_id = request.headers.get("Device-Id", "")
            auth = request.headers.get("Authorization", "")
            token = auth[7:] if auth.startswith("Bearer ") else auth
            if not token or not client_id or not device_id:
                raise MemoryAuthError("token, Client-Id and Device-Id are required")
            manager = AuthManager(config)
            payload = manager.verify_token(token, client_id, device_id)
            if not payload:
                raise MemoryAuthError("token was not accepted")
            # The token's own subject must agree with the device the request claims to be. Without this
            # check a valid token for device A could be presented alongside device B's header.
            subject = payload.get("device_id") if isinstance(payload, dict) else None
            if subject and subject != device_id:
                raise MemoryAuthError("token subject does not match the presented device")
            return device_id

        return verify

    return verifier_factory()


class CanonicalMemoryHandler:
    """Mounts the canonical memory surface onto the frozen server's aiohttp application."""

    def __init__(
        self,
        config: dict,
        service_factory: Callable[[str], Any],
        verify_identity: Callable[[web.Request], Awaitable[str]],
    ) -> None:
        self.config = config
        self.verify_identity = verify_identity
        self.http = CanonicalMemoryHttp(service_factory)

    def routes(self) -> List[web.AbstractRouteDef]:
        """Catch-all routes per method; the core owns the routing decision and its own error shape."""
        paths = [BASE_PATH, BASE_PATH + "/{tail:.*}"]
        return [
            web.route(method, path, self._handle)
            for path in paths
            for method in ("GET", "POST", "PATCH", "DELETE")
        ]

    def register(self, app: web.Application) -> None:
        app.add_routes(self.routes())

    async def _handle(self, request: web.Request) -> web.Response:
        try:
            subject_id = await self.verify_identity(request)
        except MemoryAuthError as exc:
            return self._json(error_response("UNAUTHORIZED", str(exc)))
        except Exception as exc:  # noqa: BLE001 - a verifier failure is a refusal, not a 500
            return self._json(error_response("FORBIDDEN", f"identity could not be verified: {exc}"))

        body: Optional[Dict[str, Any]] = None
        if request.method in ("POST", "PATCH"):
            try:
                body = await request.json()
            except Exception:
                return self._json(error_response("INVALID_PAYLOAD", "body is not a JSON object"))
            if not isinstance(body, dict):
                return self._json(error_response("INVALID_PAYLOAD", "body is not a JSON object"))

        response = await self.http.dispatch(
            MemoryHttpRequest(
                method=request.method,
                path=request.path,
                subject_id=subject_id,
                query=dict(request.query),
                body=body,
            ),
        )
        return self._json(response)

    @staticmethod
    def _json(response) -> web.Response:
        return web.json_response(response.body, status=response.status)
