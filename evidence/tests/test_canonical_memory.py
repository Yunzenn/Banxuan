"""Canonical memory conformance and Xiaozhi context-integration tests.

Two layers, deliberately separate:

1. **Conformance** - every case in ``evidence/contracts/canonical-memory-v1.json``
   against the Python authority. The same file is the contract the Kotlin
   executable spec must satisfy, so the file is the source of truth for the
   cross-language semantics and neither implementation is.

2. **Integration** - canonical memory through the adapter and through the *real*
   frozen ``Dialogue.get_llm_dialogue_with_memory()``, asserting what actually
   ends up in the model's request context. ``dialogue.py`` imports only the
   standard library, so the real class is loaded directly from its file: no stub
   stands in for the component whose behaviour is being claimed.

What this file does not do, and must not be made to do: call an LLM. The claim
established here is that a confirmed memory reaches the model's request context.
Whether the model then *uses* it is a different claim and stays PENDING until
there is a real model to measure.
"""

import asyncio
import importlib.util
import json
import sys
import types
from pathlib import Path
from unittest.mock import MagicMock

REPO = Path(__file__).resolve().parents[2]
SERVER_DIR = REPO / "evidence" / "server"
CONTRACT_PATH = REPO / "evidence" / "contracts" / "canonical-memory-v1.json"


def _find_upstream_server() -> Path:
    for candidate in (REPO / ".upstream").glob("*/main/xiaozhi-server"):
        if (candidate / "core" / "utils" / "dialogue.py").is_file():
            return candidate
    raise RuntimeError("frozen xiaozhi-server checkout not found under .upstream")


UPSTREAM = _find_upstream_server()


# The frozen MemoryProviderBase imports config.logger, whose setup_logging() runs
# check_config_file() at import time and demands data/.config.yaml - deployment state, not
# anything under test. Installing the stub before anything imports it keeps the test
# hermetic; relying on an ImportError to trigger the fallback does not work, because the
# real module imports fine and only fails when called. Dialogue itself needs no stub.
sys.path.insert(0, str(SERVER_DIR))
sys.path.insert(0, str(UPSTREAM))
if "config.logger" not in sys.modules:
    _config_pkg = types.ModuleType("config")
    _config_pkg.__path__ = []  # type: ignore[attr-defined]
    _logger_mod = types.ModuleType("config.logger")
    _logger_mod.setup_logging = lambda *a, **k: MagicMock()  # type: ignore[attr-defined]
    sys.modules["config"] = _config_pkg
    sys.modules["config.logger"] = _logger_mod

from canonical_memory import (  # noqa: E402
    CONFIRMED,
    MEMORY_STATUSES,
    MEMORY_TYPES,
    REJECTED,
    STAGED,
    CanonicalMemoryService,
    InMemoryCanonicalMemoryStore,
    Memory,
    MemoryError,
    MemoryQuery,
    canonical_instant,
)
from core.providers.memory.base import MemoryProviderBase  # noqa: E402
from xiaozhi_memory_provider import (  # noqa: E402
    CanonicalMemoryProvider,
    MemoryNamespace,
    MissingMemorySlotError,
    render_memory,
    require_memory_slot,
)

CONTRACT = json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))
CASES = CONTRACT["cases"]
ERROR_CODES = set(CONTRACT["errorCodes"])

_INSTANT_FIELDS = {
    "scheduledFor": "scheduled_for",
    "occurredAt": "occurred_at",
    "emotionalTone": "emotional_tone",
}


def _load_real_dialogue_module():
    """Load the frozen server's dialogue.py directly from its file.

    Importing it as a package would execute core/__init__.py and core/utils/__init__.py,
    whose side effects are irrelevant here. The file itself is the real component.
    """
    spec = importlib.util.spec_from_file_location(
        "xiaozhi_real_dialogue", UPSTREAM / "core" / "utils" / "dialogue.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


REAL_DIALOGUE = _load_real_dialogue_module()


# --------------------------------------------------------------------------------------
# fixture plumbing
# --------------------------------------------------------------------------------------


def build_memory(spec: dict) -> Memory:
    kwargs = {
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
        if key in ("scheduledFor", "occurredAt"):
            kwargs[_INSTANT_FIELDS[key]] = canonical_instant(value)
        elif key == "emotionalTone":
            kwargs["emotional_tone"] = value
        else:
            kwargs[key] = value
    return Memory(**kwargs)


def snapshot(memory: Memory) -> dict:
    """The fields the contract compares, in a stable shape."""
    result = {
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
        attr = _INSTANT_FIELDS.get(key, key)
        value = getattr(memory, attr)
        if value is not None:
            result[key] = value
    if memory.relations:
        result["relations"] = list(memory.relations)
    return result


def expected_snapshot(spec: dict) -> dict:
    return snapshot(build_memory(spec))


def _query(spec: dict) -> MemoryQuery:
    return MemoryQuery(
        text=spec.get("text"),
        types=tuple(spec.get("types", MEMORY_TYPES)),
        character_scope=spec.get("characterScope"),
        statuses=tuple(spec.get("statuses", (CONFIRMED,))),
        from_instant=canonical_instant(spec["from"]) if spec.get("from") else None,
        to_instant=canonical_instant(spec["to"]) if spec.get("to") else None,
        limit=spec.get("limit", 8),
    )


async def execute(case: dict) -> dict:
    store = InMemoryCanonicalMemoryStore()
    service = CanonicalMemoryService(store)
    for spec in case.get("initial", []):
        await store.put(build_memory(spec))

    outcome = None
    forget_results = []
    recalls = []
    listings = []
    error = None

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
                recalls.append([r.id for r in await service.recall(_query(op.get("query", {})))])
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


def check_case(case: dict, result: dict) -> None:
    expect = case["expect"]
    name = case["name"]

    if "error" in expect:
        assert result["error"] == expect["error"], f"{name}: expected {expect['error']}, got {result['error']}"
        assert expect["error"] in ERROR_CODES, f"{name}: undeclared error code {expect['error']}"
    else:
        assert result["error"] is None, f"{name}: unexpected error {result['error']}"

    if "outcome" in expect:
        assert result["outcome"] == expect["outcome"], f"{name}: outcome {result['outcome']}"
    if "forgetResults" in expect:
        assert result["forget"] == expect["forgetResults"], f"{name}: forget {result['forget']}"
    if "recallBefore" in expect:
        assert result["recalls"][0] == expect["recallBefore"], f"{name}: recallBefore {result['recalls'][0]}"
    if "recallAfter" in expect:
        assert result["recalls"][-1] == expect["recallAfter"], f"{name}: recallAfter {result['recalls'][-1]}"
    if "listAfter" in expect:
        assert result["listings"][-1] == expect["listAfter"], f"{name}: listAfter {result['listings'][-1]}"

    if "store" in expect:
        actual = sorted(result["store"], key=lambda r: r["id"])
        wanted = sorted((expected_snapshot(s) for s in expect["store"]), key=lambda r: r["id"])
        assert actual == wanted, f"{name}: store mismatch\n  actual={actual}\n  wanted={wanted}"


def _run_case(case: dict) -> None:
    check_case(case, asyncio.run(execute(case)))


# --------------------------------------------------------------------------------------
# 1. conformance against the shared contract
# --------------------------------------------------------------------------------------


def test_contract_file_is_versioned_and_declares_its_error_codes():
    assert CONTRACT["contract"] == "canonical-memory"
    assert CONTRACT["version"] == 1
    assert ERROR_CODES == {
        "MEMORY_NOT_FOUND",
        "MEMORY_NOT_CONFIRMED",
        "MEMORY_NOT_STAGEABLE",
        "INVALID_TRANSITION",
    }
    assert len(CASES) >= 12


def test_every_conformance_case_passes():
    failures = []
    for case in CASES:
        try:
            _run_case(case)
        except AssertionError as exc:
            failures.append(str(exc))
    assert not failures, "conformance failures:\n" + "\n".join(failures)


# --------------------------------------------------------------------------------------
# 2. integration: canonical memory -> real Dialogue -> model request context
# --------------------------------------------------------------------------------------


def _seed_service_with_all_the_things_that_must_not_leak() -> CanonicalMemoryService:
    """One memory per hazard, plus a raw excerpt that must never be rendered."""
    store = InMemoryCanonicalMemoryStore()
    service = CanonicalMemoryService(store)
    confirmed = Memory(
        id="confirmed-1",
        type="PROFILE",
        status=CONFIRMED,
        recorded_at="2026-09-27T10:00:00Z",
        character_scope="xiaozhi",
        attribute="food.dislike",
        value="香菜",
        excerpt="原始句子-不应进入提示词",
    )
    staged = Memory(
        id="staged-1",
        type="PROFILE",
        status=STAGED,
        recorded_at="2026-09-27T10:00:00Z",
        character_scope="xiaozhi",
        attribute="food.like",
        value="芹菜",
        excerpt="x",
    )
    rejected = Memory(
        id="rejected-1",
        type="PROFILE",
        status=REJECTED,
        recorded_at="2026-09-27T10:00:00Z",
        character_scope="xiaozhi",
        attribute="food.hate",
        value="榴莲",
        excerpt="x",
    )
    other_character = Memory(
        id="other-1",
        type="PROFILE",
        status=CONFIRMED,
        recorded_at="2026-09-27T10:00:00Z",
        character_scope="second-character",
        attribute="food.dislike",
        value="西兰花",
        excerpt="x",
    )

    async def seed():
        await service.remember(confirmed)
        await service.stage([staged])
        await store.put(rejected)
        await service.remember(other_character)

    asyncio.run(seed())
    return service


def _system_content_with_injected_memory(system_prompt: str, memory_str: str) -> str:
    dialogue = REAL_DIALOGUE.Dialogue()
    dialogue.update_system_message(system_prompt)
    messages = dialogue.get_llm_dialogue_with_memory(memory_str=memory_str)
    return next(m["content"] for m in messages if m["role"] == "system")


def test_provider_is_a_real_memory_provider_base_subclass():
    assert issubclass(CanonicalMemoryProvider, MemoryProviderBase)


def test_provider_refuses_to_be_bound_without_an_explicit_character_scope():
    provider = CanonicalMemoryProvider()
    # role_id is a device today (the frozen server passes role_id=self.device_id). A device is
    # not a character, so binding without an explicit scope must fail rather than guess.
    try:
        provider.init_memory(role_id="device-1", llm=None)
    except MissingMemorySlotError:
        return
    raise AssertionError("provider accepted a device id as if it were a character scope")


def test_save_memory_is_not_a_silent_noop():
    provider = CanonicalMemoryProvider()
    try:
        asyncio.run(provider.save_memory([], session_id="s"))
    except NotImplementedError:
        return
    raise AssertionError("save_memory must not stub out the write path")


def test_previous_confirmed_memory_reaches_the_model_request_context():
    service = _seed_service_with_all_the_things_that_must_not_leak()
    provider = CanonicalMemoryProvider()
    provider.bind(MemoryNamespace(subject_id="device-1", character_scope="xiaozhi"), service)

    memory_str = asyncio.run(provider.query_memory("我晚上吃什么好"))
    prompt = "你是小智。\n<memory>\n</memory>\n"
    require_memory_slot(prompt)
    system_content = _system_content_with_injected_memory(prompt, memory_str)

    assert "香菜" in system_content, "a confirmed memory did not reach the model request context"
    assert "芹菜" not in system_content, "a STAGED candidate leaked into the model context"
    assert "榴莲" not in system_content, "a REJECTED memory leaked into the model context"
    assert "西兰花" not in system_content, "another character's memory leaked into the model context"
    assert "原始句子-不应进入提示词" not in system_content, "Provenance.excerpt leaked into the prompt"


def test_a_natural_language_utterance_is_not_used_as_a_substring_filter():
    # Regression trap. Recall's text filter is a substring match over canonical fields, and a natural
    # question shares no substring with them, so using the utterance as a filter returns nothing and
    # the companion silently appears to have forgotten. If this fails, the read path started filtering
    # on the raw query again.
    service = _seed_service_with_all_the_things_that_must_not_leak()
    provider = CanonicalMemoryProvider()
    provider.bind(MemoryNamespace(subject_id="device-1", character_scope="xiaozhi"), service)

    memory_str = asyncio.run(provider.query_memory("我晚上吃什么好"))

    assert "香菜" in memory_str, "a natural-language query filtered away every memory"


def test_renderer_never_emits_provenance_excerpt():
    for memory in (
        Memory(id="p", type="PROFILE", status=CONFIRMED, recorded_at="2026-09-27T10:00:00Z",
               attribute="food.dislike", value="香菜", excerpt="SECRET-EXCERPT"),
        Memory(id="e", type="EVENT", status=CONFIRMED, recorded_at="2026-09-27T10:00:00Z",
               title="复诊", scheduled_for="2026-10-05T02:00:00Z", excerpt="SECRET-EXCERPT"),
        Memory(id="s", type="EPISODE", status=CONFIRMED, recorded_at="2026-09-27T10:00:00Z",
               summary="和室友吵架了", occurred_at="2026-09-26T13:00:00Z", excerpt="SECRET-EXCERPT"),
        Memory(id="r", type="RELATION", status=CONFIRMED, recorded_at="2026-09-27T10:00:00Z",
               name="小李", role="室友", excerpt="SECRET-EXCERPT"),
    ):
        rendered = render_memory(memory)
        assert "SECRET-EXCERPT" not in rendered, f"{memory.type} renderer leaked provenance"
        assert rendered.strip(), f"{memory.type} rendered nothing"


def test_a_prompt_without_a_memory_slot_is_a_loud_failure_not_silent_amnesia():
    # First, demonstrate the hazard the guard exists for: the frozen injection path is a
    # re.sub over <memory>...</memory>, so with no slot the memory vanishes with no error
    # anywhere in the server.
    slotless_prompt = "你是小智。没有任何记忆占位符。\n"
    system_content = _system_content_with_injected_memory(slotless_prompt, "- 偏好 food.dislike：香菜")
    assert "香菜" not in system_content, "premise changed: the hazard no longer reproduces"

    # The guard turns that silent loss into an explicit configuration error.
    try:
        require_memory_slot(slotless_prompt)
    except MissingMemorySlotError:
        pass
    else:
        raise AssertionError("a prompt without a memory slot was accepted")

    # And the frozen default prompt does have the slot, so the stock configuration is fine.
    default_prompt = (UPSTREAM / "agent-base-prompt.txt").read_text(encoding="utf-8")
    require_memory_slot(default_prompt)


# --------------------------------------------------------------------------------------
# plain-python runner, so this file is useful without pytest installed
# --------------------------------------------------------------------------------------


if __name__ == "__main__":
    checks = [
        ("contract file", test_contract_file_is_versioned_and_declares_its_error_codes),
        ("real base class", test_provider_is_a_real_memory_provider_base_subclass),
        ("binding guard", test_provider_refuses_to_be_bound_without_an_explicit_character_scope),
        ("no silent noop", test_save_memory_is_not_a_silent_noop),
        ("renderer excludes provenance", test_renderer_never_emits_provenance_excerpt),
        ("prompt slot guard", test_a_prompt_without_a_memory_slot_is_a_loud_failure_not_silent_amnesia),
        ("no substring filter on utterances", test_a_natural_language_utterance_is_not_used_as_a_substring_filter),
        ("context integration", test_previous_confirmed_memory_reaches_the_model_request_context),
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
            _run_case(case)
            print(f"PASS  conformance:{case['name']}")
        except Exception as exc:  # noqa: BLE001
            failures += 1
            print(f"FAIL  conformance:{case['name']}: {type(exc).__name__}: {exc}")

    total = len(checks) + len(CASES)
    print(f"\n{total - failures}/{total} passed")
    sys.exit(1 if failures else 0)
