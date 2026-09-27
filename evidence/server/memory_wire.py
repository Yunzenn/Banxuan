"""The wire form of a canonical memory - the server side of the frozen HTTP boundary.

**This is a transport projection, not a third schema.** Field names, enum spellings and instant
formatting are the ones `evidence/contracts/canonical-memory-v2.json` already uses, and the mapping is
one-to-one with :class:`Memory`: nothing is invented here and nothing is dropped. A codec that invented
its own vocabulary would quietly become the second memory definition this project has spent several
increments avoiding.

Both this module and the Kotlin `MemoryWire` are exercised against the same golden payloads in
`evidence/contracts/memory-http-v1-golden.json`, so the boundary is verified on both sides rather than
assumed to agree.

Two version numbers are in play and are not the same thing: `v1` names the HTTP surface, `v2` names the
canonical semantic contract it carries.
"""

from __future__ import annotations

from typing import Any, Dict, List, Tuple

from canonical_memory import (
    EVENT,
    EPISODE,
    MEMORY_TYPES,
    PROFILE,
    RELATION,
    Memory,
    MemoryEdit,
    MemoryQuery,
    canonical_instant,
)


class MemoryWireFormatError(ValueError):
    """A payload that does not match the contract.

    Fails closed rather than guessing: a memory the server cannot parse is one it must not act on, and
    substituting a default would fabricate a fact.
    """


# ---------------------------------------------------------------------------- records


def encode_record(memory: Memory) -> Dict[str, Any]:
    wire: Dict[str, Any] = {
        "id": memory.id,
        "type": memory.type,
        "status": memory.status,
        "recordedAt": memory.recorded_at,
        "characterScope": memory.character_scope,
        "importance": memory.importance,
        "source": memory.source,
        "provenance": {
            "sessionId": memory.session_id,
            "messageId": memory.message_id,
            "excerpt": memory.excerpt,
            "extractor": memory.extractor,
        },
    }
    if memory.type == PROFILE:
        wire["attribute"] = memory.attribute
        wire["value"] = memory.value
    elif memory.type == EVENT:
        wire["title"] = memory.title
        wire["scheduledFor"] = memory.scheduled_for
        wire["location"] = memory.location
    elif memory.type == EPISODE:
        wire["summary"] = memory.summary
        wire["occurredAt"] = memory.occurred_at
        wire["emotionalTone"] = memory.emotional_tone
        wire["relations"] = list(memory.relations)
    elif memory.type == RELATION:
        wire["name"] = memory.name
        wire["role"] = memory.role
        wire["note"] = memory.note
    else:
        raise MemoryWireFormatError(f"unknown memory type {memory.type!r}")
    return wire


def decode_record(wire: Dict[str, Any]) -> Memory:
    memory_type = _required(wire, "type")
    if memory_type not in MEMORY_TYPES:
        raise MemoryWireFormatError(f"unknown memory type {memory_type!r}")
    provenance = wire.get("provenance") or {}
    if not isinstance(provenance, dict):
        raise MemoryWireFormatError("'provenance' must be an object")

    common: Dict[str, Any] = {
        "id": _required(wire, "id"),
        "type": memory_type,
        "status": _required(wire, "status"),
        "recorded_at": _instant(_required(wire, "recordedAt")),
        "character_scope": wire.get("characterScope") or "xiaozhi",
        "importance": wire.get("importance") or "NORMAL",
        "source": wire.get("source") or "CONVERSATION",
        "session_id": provenance.get("sessionId"),
        "message_id": provenance.get("messageId"),
        "excerpt": provenance.get("excerpt") or "",
        "extractor": provenance.get("extractor") or "",
    }

    if memory_type == PROFILE:
        return Memory(**common, attribute=_required(wire, "attribute"), value=_required(wire, "value"))
    if memory_type == EVENT:
        scheduled = wire.get("scheduledFor")
        return Memory(
            **common,
            title=_required(wire, "title"),
            scheduled_for=_instant(scheduled) if scheduled else None,
            location=wire.get("location"),
        )
    if memory_type == EPISODE:
        return Memory(
            **common,
            summary=_required(wire, "summary"),
            occurred_at=_instant(_required(wire, "occurredAt")),
            emotional_tone=wire.get("emotionalTone"),
            relations=tuple(wire.get("relations") or ()),
        )
    return Memory(**common, name=_required(wire, "name"), role=_required(wire, "role"), note=wire.get("note"))


# ---------------------------------------------------------------------------- query


def encode_query(query: MemoryQuery) -> Dict[str, Any]:
    return {
        "text": query.text,
        "types": list(query.types),
        "characterScope": query.character_scope,
        "statuses": list(query.statuses),
        "from": query.from_instant,
        "to": query.to_instant,
        "limit": query.limit,
    }


def decode_query(wire: Dict[str, Any]) -> MemoryQuery:
    from_instant = wire.get("from")
    to_instant = wire.get("to")
    return MemoryQuery(
        text=wire.get("text"),
        types=tuple(wire.get("types") or MEMORY_TYPES),
        character_scope=wire.get("characterScope"),
        statuses=tuple(wire.get("statuses") or ("CONFIRMED",)),
        from_instant=_instant(from_instant) if from_instant else None,
        to_instant=_instant(to_instant) if to_instant else None,
        limit=wire.get("limit") if wire.get("limit") is not None else 8,
    )


# ---------------------------------------------------------------------------- edit


def encode_edit(edit: MemoryEdit) -> Dict[str, Any]:
    wire: Dict[str, Any] = {"type": edit.type, "editedAt": edit.edited_at}
    if edit.type == PROFILE:
        wire.update(attribute=edit.attribute, value=edit.value)
    elif edit.type == EVENT:
        wire.update(title=edit.title, scheduledFor=edit.scheduled_for, location=edit.location)
    elif edit.type == EPISODE:
        wire.update(
            summary=edit.summary,
            occurredAt=edit.occurred_at,
            emotionalTone=edit.emotional_tone,
            relations=list(edit.relations),
        )
    elif edit.type == RELATION:
        wire.update(name=edit.name, role=edit.role, note=edit.note)
    else:
        raise MemoryWireFormatError(f"unknown edit type {edit.type!r}")
    return wire


def decode_edit(wire: Dict[str, Any]) -> MemoryEdit:
    edit_type = _required(wire, "type")
    edited_at = _instant(_required(wire, "editedAt"))
    if edit_type == PROFILE:
        return MemoryEdit(
            type=PROFILE,
            edited_at=edited_at,
            attribute=_required(wire, "attribute"),
            value=_required(wire, "value"),
        )
    if edit_type == EVENT:
        scheduled = wire.get("scheduledFor")
        return MemoryEdit(
            type=EVENT,
            edited_at=edited_at,
            title=_required(wire, "title"),
            scheduled_for=_instant(scheduled) if scheduled else None,
            location=wire.get("location"),
        )
    if edit_type == EPISODE:
        return MemoryEdit(
            type=EPISODE,
            edited_at=edited_at,
            summary=_required(wire, "summary"),
            occurred_at=_instant(_required(wire, "occurredAt")),
            emotional_tone=wire.get("emotionalTone"),
            relations=tuple(wire.get("relations") or ()),
        )
    if edit_type == RELATION:
        return MemoryEdit(
            type=RELATION,
            edited_at=edited_at,
            name=_required(wire, "name"),
            role=_required(wire, "role"),
            note=wire.get("note"),
        )
    raise MemoryWireFormatError(f"unknown edit type {edit_type!r}")


# ---------------------------------------------------------------------------- helpers


def _required(wire: Dict[str, Any], key: str) -> Any:
    if key not in wire or wire[key] is None:
        raise MemoryWireFormatError(f"missing {key!r}")
    return wire[key]


def _instant(text: str) -> str:
    try:
        return canonical_instant(text)
    except (ValueError, TypeError) as exc:
        raise MemoryWireFormatError(f"unparseable instant {text!r}") from exc
