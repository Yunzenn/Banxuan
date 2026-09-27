"""The memory HTTP surface against the real authority, with no web framework in the way.

This exercises `CanonicalMemoryHttp` end to end over a real `CanonicalMemoryService`: the same service
the Python conformance suite already covers. Only the web framework and the network are absent - which
is the point, since the important rules (subject comes from the authenticated identity, errors carry
canonical codes, staged candidates never leak) are not framework behaviour.

The verifier is injected. The production handler gets one built from the frozen server's `AuthManager`;
there is no "test client" bypass in it, because a bypass branch in a production handler is a bypass
branch that ships.

Runs on a fresh clone: standard library plus `evidence/server`.
"""

import asyncio
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
EVIDENCE = REPO / "evidence"

sys.path.insert(0, str(EVIDENCE / "server"))

from canonical_memory import (  # noqa: E402
    CONFIRMED,
    REJECTED,
    STAGED,
    CanonicalMemoryService,
    InMemoryCanonicalMemoryStore,
    Memory,
)
from canonical_memory_http import (  # noqa: E402
    BASE_PATH,
    INVALID_PAYLOAD,
    STATUS_BY_CODE,
    CanonicalMemoryHttp,
    MemoryHttpRequest,
)

GOLDEN = json.loads((EVIDENCE / "contracts" / "memory-http-v1-golden.json").read_text(encoding="utf-8"))

SUBJECT_A = "device-A"
SUBJECT_B = "device-B"


class Server:
    """One store, one authority per subject - the same shape the gateway is built for."""

    def __init__(self) -> None:
        self.backing = InMemoryCanonicalMemoryStore()
        self.services = {}
        self.http = CanonicalMemoryHttp(self._service_for)

    def _service_for(self, subject_id: str) -> CanonicalMemoryService:
        if subject_id not in self.services:
            self.services[subject_id] = CanonicalMemoryService(self.backing, subject_id)
        return self.services[subject_id]

    async def call(self, method, path, subject=SUBJECT_A, query=None, body=None):
        return await self.http.dispatch(
            MemoryHttpRequest(
                method=method,
                path=path,
                subject_id=subject,
                query=query or {},
                body=body,
            ),
        )

    async def seed(self, subject: str, memory: Memory) -> None:
        await self.backing.partition(subject).put(memory)


def run(coro):
    return asyncio.run(coro)


def profile_json(**overrides) -> dict:
    record = json.loads(json.dumps(GOLDEN["records"][0]["json"]))
    record.update(overrides)
    return record


# ---------------------------------------------------------------------------- routing


def test_the_collection_lists_and_confirm_reject_edit_forget_all_answer():
    async def scenario():
        server = Server()
        await server.seed(SUBJECT_A, _memory("m1", CONFIRMED))

        listed = await server.call("GET", BASE_PATH)
        # A distinct attribute on purpose: a staged candidate sharing a confirmed memory's identity is
        # absorbed by confirm() and its own id disappears, which would make the PATCH below a 404 for a
        # reason that has nothing to do with routing.
        staged = _memory("s1", STAGED, attribute="food.like")
        entered = await server.call(
            "POST",
            BASE_PATH + "/stage",
            body={"records": [_wire(staged)]},
        )
        confirmed = await server.call("POST", BASE_PATH + "/s1/confirm")
        edited = await server.call(
            "PATCH",
            BASE_PATH + "/s1",
            body={"edit": {"type": "PROFILE", "editedAt": "2026-09-28T10:00:00Z",
                           "attribute": "food.like", "value": "苦瓜"}},
        )
        forgotten = await server.call("DELETE", BASE_PATH + "/s1")
        await server.seed(SUBJECT_A, _memory("m2", STAGED))
        rejected = await server.call("POST", BASE_PATH + "/m2/reject")
        return listed, entered, confirmed, edited, forgotten, rejected

    listed, entered, confirmed, edited, forgotten, rejected = run(scenario())

    assert listed.status == 200 and [r["id"] for r in listed.body["records"]] == ["m1"]
    assert [r["id"] for r in entered.body["records"]] == ["s1"]
    assert entered.body["records"][0]["status"] == STAGED
    assert confirmed.body["record"]["status"] == CONFIRMED
    assert edited.body["outcome"] == "updated"
    assert edited.body["record"]["value"] == "苦瓜"
    assert edited.body["record"]["source"] == "USER_EDIT"
    assert edited.body["previous"]["value"] == "香菜"
    assert forgotten.body["deleted"] is True
    assert rejected.body["record"]["status"] == REJECTED


def test_an_unknown_path_or_op_is_a_typed_payload_error():
    async def scenario():
        server = Server()
        return (
            await server.call("GET", "/somewhere/else"),
            await server.call("POST", BASE_PATH + "/nonsense"),
            await server.call("PUT", BASE_PATH),
        )

    outside, unknown_op, bad_method = run(scenario())
    for response in (outside, unknown_op, bad_method):
        assert response.body["code"] == INVALID_PAYLOAD, response.body
        assert response.status == STATUS_BY_CODE[INVALID_PAYLOAD]


def test_a_malformed_payload_fails_closed():
    async def scenario():
        server = Server()
        return (
            await server.call("POST", BASE_PATH + "/stage", body={"records": "not a list"}),
            await server.call("POST", BASE_PATH + "/remember", body={}),
            await server.call("POST", BASE_PATH + "/stage", body={"records": [{"id": "x", "type": "NOPE"}]}),
            await server.call("GET", BASE_PATH, query={"statuses": "BANANA"}),
        )

    for response in run(scenario()):
        assert response.body["code"] == INVALID_PAYLOAD, response.body


# ---------------------------------------------------------------------------- the subject boundary


def test_the_subject_comes_from_the_identity_not_from_the_payload():
    async def scenario():
        server = Server()
        await server.seed(SUBJECT_A, _memory("a1", CONFIRMED))
        await server.seed(SUBJECT_B, _memory("b1", CONFIRMED))
        # A payload that tries to name another subject. It must be inert: there is no field through
        # which a caller can choose whose memory it reaches.
        attempt = await server.call(
            "POST",
            BASE_PATH + "/recall",
            subject=SUBJECT_B,
            body={"subjectId": SUBJECT_A, "subject_id": SUBJECT_A, "statuses": ["CONFIRMED"]},
        )
        return await server.call("POST", BASE_PATH + "/recall", subject=SUBJECT_A, body={}), attempt

    as_a, attempt = run(scenario())
    assert [r["id"] for r in as_a.body["records"]] == ["a1"]
    assert [r["id"] for r in attempt.body["records"]] == ["b1"], "a payload field changed the subject"


def test_two_subjects_sharing_a_character_never_see_each_others_memory():
    async def scenario():
        server = Server()
        await server.seed(SUBJECT_A, _memory("a1", CONFIRMED, value="香菜"))
        await server.seed(SUBJECT_B, _memory("b1", CONFIRMED, value="芹菜"))
        a = await server.call("GET", BASE_PATH, subject=SUBJECT_A)
        b = await server.call("GET", BASE_PATH, subject=SUBJECT_B)
        # A correction on A must not touch B, even though both are xiaozhi and both are food.dislike.
        await server.call(
            "PATCH",
            BASE_PATH + "/a1",
            subject=SUBJECT_A,
            body={"edit": {"type": "PROFILE", "editedAt": "2026-09-28T10:00:00Z",
                           "attribute": "food.dislike", "value": "苦瓜"}},
        )
        return a, b, await server.call("GET", BASE_PATH, subject=SUBJECT_B)

    a, b, b_after = run(scenario())
    assert [r["value"] for r in a.body["records"]] == ["香菜"]
    assert [r["value"] for r in b.body["records"]] == ["芹菜"]
    assert [r["value"] for r in b_after.body["records"]] == ["芹菜"], "another subject was mutated"


# ---------------------------------------------------------------------------- safety survives transport


def test_a_staged_candidate_never_reaches_a_default_recall_over_http():
    async def scenario():
        server = Server()
        await server.seed(SUBJECT_A, _memory("s1", STAGED))
        return await server.call("POST", BASE_PATH + "/recall", body={})

    response = run(scenario())
    assert response.body["records"] == [], "a candidate reached the conversation through the transport"


def test_a_rejected_memory_cannot_be_confirmed_over_http():
    async def scenario():
        server = Server()
        await server.seed(SUBJECT_A, _memory("r1", REJECTED))
        return await server.call("POST", BASE_PATH + "/r1/confirm")

    response = run(scenario())
    assert response.body["code"] == "INVALID_TRANSITION", response.body
    assert response.status == 409


def test_an_identity_conflict_is_refused_over_http_with_both_records_untouched():
    async def scenario():
        server = Server()
        await server.seed(SUBJECT_A, _memory("m1", CONFIRMED, attribute="food.dislike"))
        await server.seed(SUBJECT_A, _memory("m2", CONFIRMED, attribute="food.like", value="芹菜"))
        conflict = await server.call(
            "PATCH",
            BASE_PATH + "/m1",
            body={"edit": {"type": "PROFILE", "editedAt": "2026-09-28T10:00:00Z",
                           "attribute": "food.like", "value": "香菜"}},
        )
        return conflict, await server.call("GET", BASE_PATH)

    conflict, afterwards = run(scenario())
    assert conflict.body["code"] == "MEMORY_IDENTITY_CONFLICT"
    assert conflict.status == 409
    assert [r["attribute"] for r in afterwards.body["records"]] == ["food.dislike", "food.like"]


def test_every_canonical_error_code_has_its_declared_status():
    for case in GOLDEN["errors"]:
        code = case["body"]["code"]
        if code in STATUS_BY_CODE:
            assert STATUS_BY_CODE[code] == case["status"], code


def test_the_golden_error_bodies_are_what_the_client_reconstructs_from():
    # The surface emits `code`, `message` and whatever else it knows; the golden file may carry richer
    # bodies for a server that knows more. Both must be readable by the client, which is why the code is
    # the only field it strictly requires.
    for case in GOLDEN["errors"]:
        assert "code" in case["body"]
        assert "message" in case["body"]


# ---------------------------------------------------------------------------- fixtures


def _memory(memory_id, status, attribute="food.dislike", value="香菜"):
    from memory_wire import decode_record

    return decode_record(
        profile_json(id=memory_id, status=status, attribute=attribute, value=value),
    )


def _wire(memory):
    from memory_wire import encode_record

    return encode_record(memory)


if __name__ == "__main__":
    checks = [
        ("routing", test_the_collection_lists_and_confirm_reject_edit_forget_all_answer),
        ("unknown path/op", test_an_unknown_path_or_op_is_a_typed_payload_error),
        ("malformed payload", test_a_malformed_payload_fails_closed),
        ("subject from identity", test_the_subject_comes_from_the_identity_not_from_the_payload),
        ("subject isolation", test_two_subjects_sharing_a_character_never_see_each_others_memory),
        ("staged safety", test_a_staged_candidate_never_reaches_a_default_recall_over_http),
        ("rejected safety", test_a_rejected_memory_cannot_be_confirmed_over_http),
        ("identity conflict", test_an_identity_conflict_is_refused_over_http_with_both_records_untouched),
        ("status table", test_every_canonical_error_code_has_its_declared_status),
        ("error bodies", test_the_golden_error_bodies_are_what_the_client_reconstructs_from),
    ]
    failures = 0
    for label, check in checks:
        try:
            check()
            print(f"PASS  {label}")
        except Exception as exc:  # noqa: BLE001
            failures += 1
            print(f"FAIL  {label}: {type(exc).__name__}: {exc}")
    print(f"\n{len(checks) - failures}/{len(checks)} passed")
    sys.exit(1 if failures else 0)
