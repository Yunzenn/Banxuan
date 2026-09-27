"""Golden-payload conformance for the memory HTTP boundary, server side.

The same file is read by the Kotlin ``RemoteMemoryGatewayTest``. A round trip through this codec must
reproduce each payload byte for byte in field terms, which is what makes "the two sides agree" a checked
statement instead of an assumption.

Runs on a fresh clone: standard library plus ``evidence/server``. No upstream checkout, no network.
"""

import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
EVIDENCE = REPO / "evidence"
GOLDEN_PATH = EVIDENCE / "contracts" / "memory-http-v1-golden.json"

sys.path.insert(0, str(EVIDENCE / "server"))

from memory_wire import (  # noqa: E402
    MemoryWireFormatError,
    decode_edit,
    decode_query,
    decode_record,
    encode_edit,
    encode_query,
    encode_record,
)

GOLDEN = json.loads(GOLDEN_PATH.read_text(encoding="utf-8"))
RECORDS = GOLDEN["records"]
EDITS = GOLDEN["edits"]
QUERIES = GOLDEN["queries"]
ERRORS = GOLDEN["errors"]
NORMALIZATION = GOLDEN["normalization"]


def test_the_golden_contract_declares_both_its_own_version_and_the_schema_it_carries():
    assert GOLDEN["contract"] == "memory-http"
    assert GOLDEN["version"] == 1
    assert GOLDEN["canonicalSchema"] == 2


def test_every_golden_record_survives_a_decode_encode_round_trip():
    assert len(RECORDS) >= 7
    for case in RECORDS:
        wire = case["json"]
        assert encode_record(decode_record(wire)) == wire, case["name"]


def test_every_golden_edit_and_query_survives_a_round_trip():
    for case in EDITS:
        wire = case["json"]
        assert encode_edit(decode_edit(wire)) == wire, case["name"]
    for case in QUERIES:
        wire = case["json"]
        assert encode_query(decode_query(wire)) == wire, case["name"]


def test_instants_are_normalised_to_canonical_utc():
    for case in NORMALIZATION:
        wire = {
            "id": "m1",
            "type": "PROFILE",
            "status": "CONFIRMED",
            "recordedAt": case["input"],
            "characterScope": "xiaozhi",
            "importance": "NORMAL",
            "source": "CONVERSATION",
            "provenance": {"sessionId": None, "messageId": None, "excerpt": "", "extractor": "x"},
            "attribute": "a",
            "value": "b",
        }
        assert encode_record(decode_record(wire))["recordedAt"] == case["output"], case["name"]


def test_a_payload_that_does_not_match_the_contract_fails_closed():
    for broken in (
        {"id": "m1", "type": "NONSENSE"},
        {"id": "m1", "type": "PROFILE", "status": "MAYBE"},
        {"id": "m1", "type": "PROFILE", "status": "CONFIRMED", "recordedAt": "not-a-time"},
        {"id": "m1", "type": "PROFILE", "status": "CONFIRMED", "recordedAt": "2026-09-27T10:00:00Z"},
    ):
        try:
            decode_record(broken)
        except MemoryWireFormatError:
            continue
        raise AssertionError(f"accepted a payload it should have refused: {broken}")


def test_the_error_codes_the_http_layer_must_carry_are_declared_and_language_neutral():
    # The transport carries these codes so the client can restore a typed exception instead of guessing
    # from an HTTP status. A code list that drifted from the semantic contract would break that.
    declared = {case["body"]["code"] for case in ERRORS}
    assert {
        "MEMORY_NOT_FOUND",
        "MEMORY_NOT_CONFIRMED",
        "MEMORY_NOT_STAGEABLE",
        "INVALID_TRANSITION",
        "MEMORY_NOT_EDITABLE",
        "MEMORY_TYPE_MISMATCH",
        "MEMORY_IDENTITY_CONFLICT",
    } <= declared
    # And an unknown code must be present too, so both sides are forced to fail closed rather than
    # treat an unrecognised refusal as a success.
    assert "SOMETHING_NEW" in declared
    for case in ERRORS:
        assert case["expectException"] in {
            "MemoryNotFoundException",
            "MemoryNotConfirmedException",
            "MemoryNotStageableException",
            "MemoryTransitionException",
            "MemoryNotEditableException",
            "MemoryTypeMismatchException",
            "MemoryIdentityConflictException",
            "MemoryTransportException",
        }, case["name"]


def test_the_wire_form_carries_no_subject_field():
    # Who a memory belongs to is decided by the authenticated identity, never by the payload. A subject
    # field on the wire would let a caller read another subject's memory by editing JSON.
    blob = json.dumps(GOLDEN)
    assert "subjectId" not in blob
    assert "subject_id" not in blob


if __name__ == "__main__":
    checks = [
        ("golden metadata", test_the_golden_contract_declares_both_its_own_version_and_the_schema_it_carries),
        ("records round trip", test_every_golden_record_survives_a_decode_encode_round_trip),
        ("edits and queries round trip", test_every_golden_edit_and_query_survives_a_round_trip),
        ("instant normalization", test_instants_are_normalised_to_canonical_utc),
        ("fail closed", test_a_payload_that_does_not_match_the_contract_fails_closed),
        ("error codes", test_the_error_codes_the_http_layer_must_carry_are_declared_and_language_neutral),
        ("no subject on the wire", test_the_wire_form_carries_no_subject_field),
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
