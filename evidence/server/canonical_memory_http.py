"""The canonical memory HTTP surface, with the web framework kept at the edge.

This module is the whole protocol: routing, authentication-driven subject selection, payload decoding,
error codes. It imports **nothing from aiohttp**, so it is testable on a fresh clone with no server
environment, and so the frozen server's framework is an adapter detail rather than the specification.
`xiaozhi_memory_handler.py` is the thin aiohttp binding on top of it.

Two rules are load-bearing and are enforced here rather than in the adapter, because an adapter is
exactly where they would get forgotten:

* **The subject comes from the authenticated identity.** [MemoryHttpRequest.subject_id] is set by
  whoever verified the token; no payload or query parameter can influence it. Only character scope
  travels in the payload, which is a different axis - a character is not a user. Without this the
  subject partition built on the service side would be punctured at the first HTTP boundary by anyone
  able to edit JSON.
* **Errors carry language-neutral codes.** The Android client restores a typed exception from the code,
  so the trust surface can keep deciding what to say by catching a type instead of parsing a message or
  inspecting an HTTP status.

The wire form itself lives in `memory_wire.py`, which both languages verify against
`evidence/contracts/memory-http-v1-golden.json`.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Mapping, Optional, Sequence

from canonical_memory import (
    INVALID_TRANSITION,
    MEMORY_IDENTITY_CONFLICT,
    MEMORY_NOT_CONFIRMED,
    MEMORY_NOT_EDITABLE,
    MEMORY_NOT_FOUND,
    MEMORY_NOT_STAGEABLE,
    MEMORY_TYPE_MISMATCH,
    MEMORY_STATUSES,
    CanonicalMemoryService,
    MemoryError,
)
from memory_wire import (
    MemoryWireFormatError,
    decode_edit,
    decode_query,
    decode_record,
    encode_record,
)

#: The HTTP surface version. Distinct from the canonical schema version, which is 2.
BASE_PATH = "/xiaozhi/memory/v1"

INVALID_PAYLOAD = "INVALID_PAYLOAD"

#: HTTP status per canonical code. The code is what the client acts on; the status exists so the
#: surface behaves sensibly for anything that is not our client.
STATUS_BY_CODE: Dict[str, int] = {
    MEMORY_NOT_FOUND: 404,
    MEMORY_NOT_CONFIRMED: 400,
    MEMORY_NOT_STAGEABLE: 400,
    MEMORY_TYPE_MISMATCH: 400,
    INVALID_TRANSITION: 409,
    MEMORY_NOT_EDITABLE: 409,
    MEMORY_IDENTITY_CONFLICT: 409,
    INVALID_PAYLOAD: 400,
}


@dataclass(frozen=True)
class MemoryHttpRequest:
    """A decoded request.

    ``subject_id`` is not part of the payload: it is supplied by the caller after the token has been
    verified, which is why this type has no field a client could set it through.
    """

    method: str
    path: str
    subject_id: str
    query: Mapping[str, str] = field(default_factory=dict)
    body: Optional[Mapping[str, Any]] = None


@dataclass(frozen=True)
class MemoryHttpResponse:
    status: int
    body: Dict[str, Any]


def error_response(code: str, message: str, memory_id: Optional[str] = None) -> MemoryHttpResponse:
    """One error shape for every failure, keyed by the canonical code.

    A structured body carries whatever the surface knows. The client reconstructs the typed exception
    from the code and uses these fields where present, falling back when a leaner server omits them -
    which is why emitting `code` and `message` alone would still be usable, and emitting a bare
    "something failed" would not.
    """
    body: Dict[str, Any] = {"code": code, "message": message}
    if memory_id:
        body["id"] = memory_id
    return MemoryHttpResponse(STATUS_BY_CODE.get(code, 500), body)


class CanonicalMemoryHttp:
    """Routes the canonical memory surface onto one [CanonicalMemoryService] per subject.

    The service factory is a callable rather than a service so that this object cannot accidentally hold
    one subject's partition and answer for another. Each request resolves its own.
    """

    def __init__(self, service_factory: Callable[[str], CanonicalMemoryService]) -> None:
        self._service_factory = service_factory

    async def dispatch(self, request: MemoryHttpRequest) -> MemoryHttpResponse:
        segments = self._segments(request.path)
        if segments is None:
            return error_response(INVALID_PAYLOAD, f"not a memory path: {request.path}")

        service = self._service_factory(request.subject_id)
        try:
            return await self._route(request, segments, service)
        except MemoryError as exc:
            return error_response(exc.code, str(exc), self._id_from(segments))
        except MemoryWireFormatError as exc:
            # Fail closed: a payload the surface cannot read is one it must not act on.
            return error_response(INVALID_PAYLOAD, str(exc))
        except (KeyError, TypeError, ValueError) as exc:
            return error_response(INVALID_PAYLOAD, f"malformed payload: {exc}")

    # ------------------------------------------------------------------ routing

    async def _route(
        self,
        request: MemoryHttpRequest,
        segments: Sequence[str],
        service: CanonicalMemoryService,
    ) -> MemoryHttpResponse:
        method = request.method.upper()

        if not segments:
            if method != "GET":
                return error_response(INVALID_PAYLOAD, f"{method} is not allowed on the collection")
            return MemoryHttpResponse(200, {"records": await self._list(request, service)})

        if len(segments) == 1:
            head = segments[0]
            if head == "stage" and method == "POST":
                records = [decode_record(item) for item in self._records_field(request)]
                staged = await service.stage(records)
                return MemoryHttpResponse(200, {"records": [encode_record(r) for r in staged]})

            if head == "remember" and method == "POST":
                outcome = await service.remember(decode_record(self._record_field(request, "record")))
                return MemoryHttpResponse(200, self._remember_body(outcome))

            if head == "recall" and method == "POST":
                query = decode_query(dict(request.body or {}))
                records = await service.recall(query)
                return MemoryHttpResponse(200, {"records": [encode_record(r) for r in records]})

            if method in ("PATCH", "DELETE"):
                return await self._by_id(request, head, service)

            return error_response(INVALID_PAYLOAD, f"unsupported operation on {head!r}")

        if len(segments) == 2:
            memory_id, action = segments
            if action == "confirm" and method == "POST":
                return MemoryHttpResponse(200, {"record": encode_record(await service.confirm(memory_id))})
            if action == "reject" and method == "POST":
                return MemoryHttpResponse(200, {"record": encode_record(await service.reject(memory_id))})
            return error_response(INVALID_PAYLOAD, f"unsupported action {action!r}")

        return error_response(INVALID_PAYLOAD, "path has too many segments")

    async def _by_id(
        self,
        request: MemoryHttpRequest,
        memory_id: str,
        service: CanonicalMemoryService,
    ) -> MemoryHttpResponse:
        if request.method.upper() == "DELETE":
            return MemoryHttpResponse(200, {"deleted": await service.forget(memory_id)})

        edit = decode_edit(self._record_field(request, "edit"))
        outcome = await service.edit(memory_id, edit)
        body: Dict[str, Any] = {"outcome": outcome.kind, "record": encode_record(outcome.memory)}
        if outcome.kind == "updated" and outcome.previous is not None:
            # `previous` is what lets the client rebuild a faithful "updated" outcome instead of
            # inventing a previous record it never saw.
            body["previous"] = encode_record(outcome.previous)
        return MemoryHttpResponse(200, body)

    async def _list(self, request: MemoryHttpRequest, service: CanonicalMemoryService) -> List[Dict[str, Any]]:
        raw_statuses = request.query.get("statuses")
        statuses = tuple(s for s in (raw_statuses or "").split(",") if s) or MEMORY_STATUSES
        for status in statuses:
            if status not in MEMORY_STATUSES:
                raise MemoryWireFormatError(f"unknown status {status!r}")
        character_scope = request.query.get("characterScope") or None
        records = await service.list(statuses=statuses, character_scope=character_scope)
        return [encode_record(r) for r in records]

    # ------------------------------------------------------------------ payload helpers

    def _records_field(self, request: MemoryHttpRequest) -> List[Dict[str, Any]]:
        records = (request.body or {}).get("records")
        if not isinstance(records, list):
            raise MemoryWireFormatError("'records' must be a list")
        for item in records:
            if not isinstance(item, dict):
                raise MemoryWireFormatError("every entry in 'records' must be an object")
        return records

    def _record_field(self, request: MemoryHttpRequest, key: str) -> Dict[str, Any]:
        value = (request.body or {}).get(key)
        if not isinstance(value, dict):
            raise MemoryWireFormatError(f"'{key}' must be an object")
        return value

    @staticmethod
    def _remember_body(outcome) -> Dict[str, Any]:
        body: Dict[str, Any] = {"outcome": outcome.kind, "record": encode_record(outcome.memory)}
        if outcome.kind == "updated" and outcome.previous is not None:
            body["previous"] = encode_record(outcome.previous)
        return body

    @staticmethod
    def _segments(path: str) -> Optional[List[str]]:
        clean = path.split("?", 1)[0].rstrip("/")
        if clean == BASE_PATH:
            return []
        if not clean.startswith(BASE_PATH + "/"):
            return None
        return [part for part in clean[len(BASE_PATH) + 1:].split("/") if part]

    @staticmethod
    def _id_from(segments: Sequence[str]) -> Optional[str]:
        return segments[0] if segments else None
