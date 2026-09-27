"""Fixture harness for the shared canonical-memory contract.

Holds the parsing, record construction, snapshot comparison and case execution
used by the contract conformance tests. Deliberately free of any dependency on
the frozen Xiaozhi checkout, so a fresh clone can run the shared contract.

The contract file is the source of truth for cross-language semantics. Neither the
Kotlin executable spec nor the Python authority is: both must satisfy it.
"""

from __future__ import annotations

import asyncio
import json
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional

EVIDENCE_DIR = Path(__file__).resolve().parents[1]
REPO = EVIDENCE_DIR.parent
SERVER_DIR = EVIDENCE_DIR / "server"
CONTRACT_PATH = EVIDENCE_DIR / "contracts" / "canonical-memory-v1.json"

if str(SERVER_DIR) not in sys.path:
    sys.path.insert(0, str(SERVER_DIR))

from canonical_memory import (  # noqa: E402
    CONFIRMED,
    MEMORY_STATUSES,
    MEMORY_TYPES,
    CanonicalMemoryService,
    InMemoryCanonicalMemoryStore,
    Memory,
    MemoryError,
    MemoryQuery,
    canonical_instant,
)

#: The shared contract describes the semantics *within one subject*. Cross-subject
#: isolation is a separate infrastructure contract, tested natively on each side,
#: because it must not be encoded into the canonical schema.
CONTRACT_SUBJECT = "subject-1"

CONTRACT = json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))
CASES: List[Dict[str, Any]] = CONTRACT["cases"]
ERROR_CODES = set(CONTRACT["errorCodes"])

_INSTANT_FIELDS = {
    "scheduledFor": "scheduled_for",
    "occurredAt": "occurred_at",
    "emotionalTone": "emotional_tone",
}


def build_memory(spec: Dict[str, Any]) -> Memory:
    """One record from its contract representation."""
    kwargs: Dict[str, Any] = {
        "id": spec["id"],
        "type": spec["type"],
        "status": spec["status"],
        "recorded_at": canonical_instant(spec["recordedAt"]),
        "character_scope": spec.get("characterScope", "xiaozhi"),
        "excerpt": spec.get("excerpt", ""),
        "relations": tuple(spec.get("relations", ())),
    }
    for key, value in spec.items():
        if key in ("id", "type", "status", "recordedAt", "characterScope", "excerpt", "relations"):
            continue
        if key in _INSTANT_FIELDS:
            kwargs[_INSTANT_FIELDS[key]] = canonical_instant(value)
        else:
            kwargs[key] = value
    return Memory(**kwargs)


def snapshot(memory: Memory) -> Dict[str, Any]:
    """The fields the contract compares, in a stable shape."""
    result: Dict[str, Any] = {
        "id": memory.id,
        "type": memory.type,
        "status": memory.status,
        "recordedAt": memory.recorded_at,
        "characterScope": memory.character_scope,
        "excerpt": memory.excerpt,
    }
    for key in (
        "attribute",
        "value",
        "title",
        "scheduledFor",
        "location",
        "summary",
        "occurredAt",
        "emotionalTone",
        "name",
        "role",
        "note",
    ):
        value = getattr(memory, _INSTANT_FIELDS.get(key, key))
        if value is not None:
            result[key] = value
    if memory.relations:
        result["relations"] = list(memory.relations)
    return result


def expected_snapshot(spec: Dict[str, Any]) -> Dict[str, Any]:
    return snapshot(build_memory(spec))


def to_query(spec: Dict[str, Any]) -> MemoryQuery:
    return MemoryQuery(
        text=spec.get("text"),
        types=tuple(spec.get("types", MEMORY_TYPES)),
        character_scope=spec.get("characterScope"),
        statuses=tuple(spec.get("statuses", (CONFIRMED,))),
        from_instant=canonical_instant(spec["from"]) if spec.get("from") else None,
        to_instant=canonical_instant(spec["to"]) if spec.get("to") else None,
        limit=spec.get("limit", 8),
    )


async def execute(case: Dict[str, Any]) -> Dict[str, Any]:
    """Run one contract case and return everything the expectations can look at."""
    backing = InMemoryCanonicalMemoryStore()
    service = CanonicalMemoryService(backing, CONTRACT_SUBJECT)
    store = backing.partition(CONTRACT_SUBJECT)
    for spec in case.get("initial", []):
        await store.put(build_memory(spec))

    outcome: Optional[str] = None
    forget_results: List[bool] = []
    recalls: List[List[str]] = []
    listings: List[List[str]] = []
    error: Optional[str] = None

    try:
        for op in case["operations"]:
            name = op["op"]
            if name == "remember":
                outcome = (await service.remember(build_memory(op["record"]))).kind
            elif name == "stage":
                await service.stage([build_memory(op["record"])])
            elif name == "confirm":
                await service.confirm(op["id"])
            elif name == "reject":
                await service.reject(op["id"])
            elif name == "forget":
                forget_results.append(await service.forget(op["id"]))
            elif name == "recall":
                recalls.append([r.id for r in await service.recall(to_query(op.get("query", {})))])
            elif name == "list":
                listings.append(
                    [
                        r.id
                        for r in await service.list(statuses=tuple(op.get("statuses", MEMORY_STATUSES)))
                    ]
                )
            else:
                raise AssertionError(f"unknown op {name!r} in case {case['name']}")
    except MemoryError as exc:
        error = exc.code

    return {
        "error": error,
        "outcome": outcome,
        "forget": forget_results,
        "recalls": recalls,
        "listings": listings,
        "store": [snapshot(r) for r in await store.list()],
    }


def check_case(case: Dict[str, Any], result: Dict[str, Any]) -> None:
    """Assert one executed case against its expectations.

    Only two comparisons care about order: the store snapshot is sorted by id, and
    recall/list results are taken as the gateway returns them (newest first), which
    is itself part of the contract.
    """
    expect = case["expect"]
    name = case["name"]

    if "error" in expect:
        assert result["error"] == expect["error"], (
            f"{name}: expected error {expect['error']}, got {result['error']}"
        )
        assert expect["error"] in ERROR_CODES, f"{name}: undeclared error code {expect['error']}"
    else:
        assert result["error"] is None, f"{name}: unexpected error {result['error']}"

    if "outcome" in expect:
        assert result["outcome"] == expect["outcome"], f"{name}: outcome {result['outcome']}"
    if "forgetResults" in expect:
        assert result["forget"] == expect["forgetResults"], f"{name}: forget {result['forget']}"
    if "recallBefore" in expect:
        assert result["recalls"][0] == expect["recallBefore"], (
            f"{name}: recallBefore {result['recalls'][0]}"
        )
    if "recallAfter" in expect:
        assert result["recalls"][-1] == expect["recallAfter"], (
            f"{name}: recallAfter {result['recalls'][-1]}"
        )
    if "listAfter" in expect:
        assert result["listings"][-1] == expect["listAfter"], (
            f"{name}: listAfter {result['listings'][-1]}"
        )

    if "store" in expect:
        actual = sorted(result["store"], key=lambda r: r["id"])
        wanted = sorted((expected_snapshot(s) for s in expect["store"]), key=lambda r: r["id"])
        assert actual == wanted, f"{name}: store mismatch\n  actual={actual}\n  wanted={wanted}"


def run_case(case: Dict[str, Any]) -> None:
    check_case(case, asyncio.run(execute(case)))


def run_all_cases() -> List[str]:
    """Run every shared case; return the failure messages, empty when all pass."""
    failures = []
    for case in CASES:
        try:
            run_case(case)
        except AssertionError as exc:
            failures.append(str(exc))
    return failures
