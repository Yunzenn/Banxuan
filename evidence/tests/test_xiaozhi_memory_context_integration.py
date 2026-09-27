"""Xiaozhi integration evidence: canonical memory through the real injection path.

Requires the frozen server checkout under ``.upstream``, so this file is **not**
part of fresh-clone CI on purpose. This repository's required gate should not depend
on a second public repository being reachable; the shared semantic contract is
enforced in CI by ``test_canonical_memory_conformance.py`` instead.

``dialogue.py`` imports only the standard library, so the real class is loaded
directly from its file and no stub stands in for the component whose behaviour is
being claimed.

What this file establishes: a confirmed memory reaches the model's request context.
It does not call an LLM, and no fake LLM was introduced to claim anything about what
the model then does with it.
"""

import asyncio
import importlib.util
import sys
import types
from pathlib import Path
from unittest.mock import MagicMock

TESTS_DIR = Path(__file__).resolve().parent
REPO = TESTS_DIR.parents[1]
SERVER_DIR = REPO / "evidence" / "server"


def _find_upstream_server() -> Path:
    for candidate in (REPO / ".upstream").glob("*/main/xiaozhi-server"):
        if (candidate / "core" / "utils" / "dialogue.py").is_file():
            return candidate
    raise RuntimeError(
        "frozen xiaozhi-server checkout not found under .upstream; this file is "
        "frozen-upstream evidence and cannot run on a fresh clone. Run "
        "test_canonical_memory_conformance.py instead - it needs no checkout."
    )


UPSTREAM = _find_upstream_server()

# The frozen MemoryProviderBase imports config.logger, whose setup_logging() runs
# check_config_file() at import time and demands data/.config.yaml - deployment state,
# not anything under test. Installing the stub before anything imports it keeps this
# hermetic; relying on an ImportError to trigger a fallback does not work, because the
# real module imports fine and only fails when called. Dialogue needs no stub at all.
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
    REJECTED,
    STAGED,
    CanonicalMemoryService,
    InMemoryCanonicalMemoryStore,
    Memory,
    MemoryNamespace,
    SubjectPartitionMismatch,
)
from core.providers.memory.base import MemoryProviderBase  # noqa: E402
from xiaozhi_memory_provider import (  # noqa: E402
    CanonicalMemoryProvider,
    MissingMemorySlotError,
    render_memory,
    require_memory_slot,
)

SUBJECT = "device-1"
OTHER_SUBJECT = "device-2"
XIAOZHI = "xiaozhi"


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


def _seed_service_with_all_the_things_that_must_not_leak() -> CanonicalMemoryService:
    """One memory per hazard, plus a raw excerpt that must never be rendered."""
    backing = InMemoryCanonicalMemoryStore()
    service = CanonicalMemoryService(backing, SUBJECT)
    store = backing.partition(SUBJECT)
    confirmed = Memory(
        id="confirmed-1", type="PROFILE", status=CONFIRMED,
        recorded_at="2026-09-27T10:00:00Z", character_scope=XIAOZHI,
        attribute="food.dislike", value="香菜", excerpt="原始句子-不应进入提示词",
    )
    staged = Memory(
        id="staged-1", type="PROFILE", status=STAGED,
        recorded_at="2026-09-27T10:00:00Z", character_scope=XIAOZHI,
        attribute="food.like", value="芹菜", excerpt="x",
    )
    rejected = Memory(
        id="rejected-1", type="PROFILE", status=REJECTED,
        recorded_at="2026-09-27T10:00:00Z", character_scope=XIAOZHI,
        attribute="food.hate", value="榴莲", excerpt="x",
    )
    other_character = Memory(
        id="other-1", type="PROFILE", status=CONFIRMED,
        recorded_at="2026-09-27T10:00:00Z", character_scope="second-character",
        attribute="food.dislike", value="西兰花", excerpt="x",
    )

    async def seed():
        await service.remember(confirmed)
        await service.stage([staged])
        await store.put(rejected)
        await service.remember(other_character)

    asyncio.run(seed())
    return service


def _bound_provider(service: CanonicalMemoryService, subject: str = SUBJECT) -> CanonicalMemoryProvider:
    provider = CanonicalMemoryProvider()
    provider.bind(MemoryNamespace(subject_id=subject, character_scope=XIAOZHI), service)
    return provider


def _system_content_with_injected_memory(system_prompt: str, memory_str: str) -> str:
    dialogue = REAL_DIALOGUE.Dialogue()
    dialogue.update_system_message(system_prompt)
    messages = dialogue.get_llm_dialogue_with_memory(memory_str=memory_str)
    return next(m["content"] for m in messages if m["role"] == "system")


# --------------------------------------------------------------------------------------
# adapter shape
# --------------------------------------------------------------------------------------


def test_provider_is_a_real_memory_provider_base_subclass():
    assert issubclass(CanonicalMemoryProvider, MemoryProviderBase)


def test_provider_refuses_to_be_bound_without_an_explicit_character_scope():
    provider = CanonicalMemoryProvider()
    try:
        provider.init_memory(role_id=SUBJECT, llm=None)
    except MissingMemorySlotError:
        return
    raise AssertionError("provider accepted a device id as if it were a character scope")


def test_binding_a_namespace_from_another_subject_is_refused():
    service = _seed_service_with_all_the_things_that_must_not_leak()
    provider = CanonicalMemoryProvider()
    try:
        provider.bind(MemoryNamespace(subject_id=OTHER_SUBJECT, character_scope=XIAOZHI), service)
    except SubjectPartitionMismatch:
        return
    raise AssertionError("a namespace was bound to a service partitioned for another subject")


def test_role_id_must_agree_with_the_bound_subject():
    service = _seed_service_with_all_the_things_that_must_not_leak()
    provider = _bound_provider(service)
    try:
        provider.init_memory(role_id=OTHER_SUBJECT, llm=None)
    except SubjectPartitionMismatch:
        return
    raise AssertionError("role_id was read from the frozen server and then ignored")


def test_save_memory_is_not_a_silent_noop():
    provider = CanonicalMemoryProvider()
    try:
        asyncio.run(provider.save_memory([], session_id="s"))
    except NotImplementedError:
        return
    raise AssertionError("save_memory must not stub out the write path")


# --------------------------------------------------------------------------------------
# what reaches the model
# --------------------------------------------------------------------------------------


def test_previous_confirmed_memory_reaches_the_model_request_context():
    service = _seed_service_with_all_the_things_that_must_not_leak()
    provider = _bound_provider(service)

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
    # Regression trap. Recall's text filter is a substring match over canonical fields, and
    # a natural question shares no substring with them, so using the utterance as a filter
    # returns nothing and the companion silently appears to have forgotten.
    service = _seed_service_with_all_the_things_that_must_not_leak()
    provider = _bound_provider(service)

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
    # First reproduce the hazard the guard exists for: the frozen injection path is a re.sub
    # over <memory>...</memory>, so with no slot the memory vanishes and no error is raised
    # anywhere in the server.
    slotless_prompt = "你是小智。没有任何记忆占位符。\n"
    system_content = _system_content_with_injected_memory(slotless_prompt, "- 偏好 food.dislike：香菜")
    assert "香菜" not in system_content, "premise changed: the hazard no longer reproduces"

    try:
        require_memory_slot(slotless_prompt)
    except MissingMemorySlotError:
        pass
    else:
        raise AssertionError("a prompt without a memory slot was accepted")

    # The frozen default prompt does have the slot, so the stock configuration is fine.
    require_memory_slot((UPSTREAM / "agent-base-prompt.txt").read_text(encoding="utf-8"))


if __name__ == "__main__":
    checks = [
        ("real base class", test_provider_is_a_real_memory_provider_base_subclass),
        ("character scope required", test_provider_refuses_to_be_bound_without_an_explicit_character_scope),
        ("subject mismatch refused", test_binding_a_namespace_from_another_subject_is_refused),
        ("role_id must match subject", test_role_id_must_agree_with_the_bound_subject),
        ("no silent noop", test_save_memory_is_not_a_silent_noop),
        ("renderer excludes provenance", test_renderer_never_emits_provenance_excerpt),
        ("no substring filter on utterances", test_a_natural_language_utterance_is_not_used_as_a_substring_filter),
        ("prompt slot guard", test_a_prompt_without_a_memory_slot_is_a_loud_failure_not_silent_amnesia),
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
    print(f"\n{len(checks) - failures}/{len(checks)} passed")
    sys.exit(1 if failures else 0)
