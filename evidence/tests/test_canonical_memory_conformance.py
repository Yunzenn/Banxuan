"""Shared canonical-memory contract conformance, plus the subject isolation contract.

Runs on a fresh clone: standard library plus ``evidence/server/canonical_memory.py``.
Nothing here needs the frozen Xiaozhi checkout, so this is what CI enforces. The
Xiaozhi integration evidence lives in
``test_xiaozhi_memory_context_integration.py`` and is deliberately not required by
fresh-clone CI, so this repository's gate does not depend on a second public
repository being reachable.

Two contracts, kept separate on purpose:

* the **semantic** contract (``evidence/contracts/canonical-memory-v1.json``) is
  language-neutral and single-subject - the Kotlin executable spec must satisfy it
  too, so it holds only what both languages share;
* the **subject isolation** contract is infrastructure, native to the authority, and
  deliberately NOT in the shared file, because encoding it would push a storage
  concern into the canonical schema.
"""

import asyncio
import sys
from pathlib import Path

TESTS_DIR = Path(__file__).resolve().parent
# Both paths are added explicitly rather than relying on the harness to have been imported
# first: this file must be runnable as a plain script by CI, on its own.
sys.path.insert(0, str(TESTS_DIR))
sys.path.insert(0, str(TESTS_DIR.parent / "server"))

from canonical_memory import (  # noqa: E402
    CONFIRMED,
    CanonicalMemoryService,
    InMemoryCanonicalMemoryStore,
    Memory,
)
from canonical_memory_contract import (  # noqa: E402
    CASES,
    CONTRACT,
    ERROR_CODES,
    run_all_cases,
)

SUBJECT_A = "subject-A"
SUBJECT_B = "subject-B"
XIAOZHI = "xiaozhi"


# --------------------------------------------------------------------------------------
# the shared, language-neutral contract
# --------------------------------------------------------------------------------------


def test_contract_file_is_versioned_and_declares_its_error_codes():
    assert CONTRACT["contract"] == "canonical-memory"
    assert CONTRACT["version"] == 2
    assert ERROR_CODES == {
        "MEMORY_NOT_FOUND",
        "MEMORY_NOT_CONFIRMED",
        "MEMORY_NOT_STAGEABLE",
        "INVALID_TRANSITION",
        "MEMORY_NOT_EDITABLE",
        "MEMORY_TYPE_MISMATCH",
        "MEMORY_IDENTITY_CONFLICT",
    }
    assert len(CASES) >= 27


def test_every_shared_conformance_case_passes():
    failures = run_all_cases()
    assert not failures, "conformance failures:\n" + "\n".join(failures)


def test_the_shared_contract_encodes_no_subject_concern():
    # Subject is a storage partition boundary, not part of what a memory is. If it ever
    # appears in the shared file, the schema has grown an infrastructure concern and the
    # Kotlin side would have to model it.
    blob = str(CONTRACT)
    assert "subjectId" not in blob
    assert "subject_id" not in blob


# --------------------------------------------------------------------------------------
# subject isolation: two subjects sharing a character name must stay disjoint
# --------------------------------------------------------------------------------------


def _two_subjects_on_one_store():
    backing = InMemoryCanonicalMemoryStore()
    return backing, CanonicalMemoryService(backing, SUBJECT_A), CanonicalMemoryService(backing, SUBJECT_B)


def _profile(value: str, memory_id: str, subject_recorded: str = "2026-09-27T10:00:00Z") -> Memory:
    return Memory(
        id=memory_id,
        type="PROFILE",
        status=CONFIRMED,
        recorded_at=subject_recorded,
        character_scope=XIAOZHI,
        attribute="food.dislike",
        value=value,
        excerpt="x",
    )


def test_two_subjects_sharing_a_character_scope_do_not_see_each_other():
    _backing, a, b = _two_subjects_on_one_store()
    asyncio.run(a.remember(_profile("香菜", "a-1")))
    asyncio.run(b.remember(_profile("芹菜", "b-1")))

    assert [m.value for m in asyncio.run(a.recall())] == ["香菜"]
    assert [m.value for m in asyncio.run(b.recall())] == ["芹菜"]


def test_a_correction_in_one_subject_does_not_mutate_another():
    _backing, a, b = _two_subjects_on_one_store()
    asyncio.run(a.remember(_profile("香菜", "a-1")))
    asyncio.run(b.remember(_profile("芹菜", "b-1")))

    asyncio.run(a.remember(_profile("苦瓜", "a-2", subject_recorded="2026-09-28T10:00:00Z")))

    assert [m.value for m in asyncio.run(a.recall())] == ["苦瓜"]
    assert [m.value for m in asyncio.run(b.recall())] == ["芹菜"], "another subject was mutated"


def test_a_subject_cannot_reach_another_subjects_record_by_id():
    _backing, a, b = _two_subjects_on_one_store()
    asyncio.run(a.remember(_profile("香菜", "a-1")))

    # Same id, wrong partition: the record is simply not there.
    assert asyncio.run(b.forget("a-1")) is False
    assert [m.value for m in asyncio.run(a.recall())] == ["香菜"]

    assert asyncio.run(b.list()) == []


def test_two_subjects_do_not_deduplicate_against_each_other():
    _backing, a, b = _two_subjects_on_one_store()
    first = asyncio.run(a.remember(_profile("香菜", "a-1")))
    second = asyncio.run(b.remember(_profile("香菜", "b-1")))

    # Identical fact, identical character, different subject: two records, both created.
    assert first.kind == "created"
    assert second.kind == "created"
    assert second.memory.id == "b-1"


# --------------------------------------------------------------------------------------
# plain-python runner, so this file is useful without pytest installed
# --------------------------------------------------------------------------------------


if __name__ == "__main__":
    checks = [
        ("contract metadata", test_contract_file_is_versioned_and_declares_its_error_codes),
        ("subject not in shared contract", test_the_shared_contract_encodes_no_subject_concern),
        ("subjects stay disjoint", test_two_subjects_sharing_a_character_scope_do_not_see_each_other),
        ("correction is subject-local", test_a_correction_in_one_subject_does_not_mutate_another),
        ("no cross-subject id access", test_a_subject_cannot_reach_another_subjects_record_by_id),
        ("no cross-subject dedup", test_two_subjects_do_not_deduplicate_against_each_other),
    ]
    failures = 0
    for label, fn in checks:
        try:
            fn()
            print(f"PASS  {label}")
        except Exception as exc:  # noqa: BLE001
            failures += 1
            print(f"FAIL  {label}: {type(exc).__name__}: {exc}")

    for case in CASES:
        try:
            from canonical_memory_contract import run_case

            run_case(case)
            print(f"PASS  conformance:{case['name']}")
        except Exception as exc:  # noqa: BLE001
            failures += 1
            print(f"FAIL  conformance:{case['name']}: {type(exc).__name__}: {exc}")

    total = len(checks) + len(CASES)
    print(f"\n{total - failures}/{total} passed")
    sys.exit(1 if failures else 0)
