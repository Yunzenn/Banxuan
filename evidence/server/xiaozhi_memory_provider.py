"""Thin adapter from CanonicalMemoryService to the frozen Xiaozhi server.

This module is deliberately thin, and thinner than it looks. It contains the
model-facing renderer, the prompt-slot guard and the namespace type. It contains
no de-duplication, no lifecycle rule and no scoping rule of its own: every one of
those is a call into :class:`CanonicalMemoryService`.

The reason for that discipline is concrete. The product already has two
implementations of the canonical semantics - the Kotlin executable spec and the
Python authority - held together by a shared conformance contract. An adapter
with its own copy of the rules would be a third, and the shared contract would
stop meaning anything.

Reuse note: the injection seam reused here is the frozen server's own
``MemoryProviderBase`` plus ``Dialogue.get_llm_dialogue_with_memory()``. This
module does not build a parallel context framework.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import List, Optional, Sequence

from core.providers.memory.base import MemoryProviderBase

from canonical_memory import (
    CONFIRMED,
    EVENT,
    EPISODE,
    PROFILE,
    RELATION,
    CanonicalMemoryService,
    Memory,
    MemoryNamespace,
    MemoryQuery,
    SubjectPartitionMismatch,
)

#: The placeholder ``Dialogue.get_llm_dialogue_with_memory()`` substitutes into.
#: It matches with DOTALL, exactly as the frozen server does.
MEMORY_SLOT = re.compile(r"<memory>.*?</memory>", re.DOTALL)


class MissingMemorySlotError(Exception):
    """The provider is active but the system prompt has no memory slot.

    Raised instead of continuing, because the frozen injection path is a
    ``re.sub`` over this pattern: with no slot present the substitution silently
    matches nothing, no error is raised anywhere, and the companion simply
    behaves as if she had never met the user. A quiet amnesia bug is far worse
    than a loud configuration error.
    """


def require_memory_slot(system_prompt: str) -> None:
    """Fail loudly when a canonical provider cannot possibly inject anything."""
    if not MEMORY_SLOT.search(system_prompt):
        raise MissingMemorySlotError(
            "canonical memory provider is enabled but the system prompt contains no "
            "<memory>...</memory> slot; injected memories would be discarded silently"
        )


def render_memory(memory: Memory) -> str:
    """Render one memory for the model.

    Only canonical semantic fields appear. ``Provenance.excerpt`` is deliberately
    absent: it is the user's raw sentence, kept for the "我的记忆" audit surface so
    she can see why the companion believes something. Putting it in the prompt
    would spend tokens on unredacted user text and re-inject raw utterance into
    the system context, which is neither what the canonical schema promises nor
    what the trust surface is for.
    """
    if memory.type == PROFILE:
        return f"- 偏好 {memory.attribute}：{memory.value}"
    if memory.type == EVENT:
        when = memory.scheduled_for or "时间未定"
        where = f" 地点：{memory.location}" if memory.location else ""
        return f"- 事件 {memory.title}（{when}）{where}".rstrip()
    if memory.type == EPISODE:
        parts = [f"- 经历 {memory.summary}（{memory.occurred_at}）"]
        if memory.emotional_tone:
            parts.append(f"情绪：{memory.emotional_tone}")
        if memory.relations:
            parts.append("相关：" + "、".join(memory.relations))
        return " ".join(parts)
    return f"- 关系 {memory.name}（{memory.role}）" + (f" {memory.note}" if memory.note else "")


class CanonicalMemoryProvider(MemoryProviderBase):
    """Read-path adapter: canonical memory -> the string Xiaozhi injects.

    ``query_memory`` asks the service for CONFIRMED records **explicitly** rather
    than relying on the query default, because this is the safety boundary: staged
    candidates, rejected records and other characters' memories must not reach the
    model through this path, and a default is something a future refactor can
    change without noticing.
    """

    def __init__(self, config=None, service: Optional[CanonicalMemoryService] = None,
                 namespace: Optional[MemoryNamespace] = None) -> None:
        super().__init__(config)
        self._service = service
        self._namespace = namespace

    def bind(self, namespace: MemoryNamespace, service: CanonicalMemoryService) -> None:
        if namespace.subject_id != service.subject_id:
            raise SubjectPartitionMismatch(
                f"namespace subject {namespace.subject_id!r} does not match the service "
                f"partition {service.subject_id!r}"
            )
        self._namespace = namespace
        self._service = service

    def init_memory(self, role_id, llm, **kwargs):
        super().init_memory(role_id, llm, **kwargs)
        # The frozen server passes device_id here. Reaching a channel whose memory
        # would be scoped by character requires the character too, so a namespace
        # must have been bound explicitly; guessing one would inject the wrong
        # character's memories once a second character exists.
        if self._namespace is None:
            raise MissingMemorySlotError(
                "CanonicalMemoryProvider requires an explicit MemoryNamespace; "
                "role_id alone is a device, not a character"
            )
        # role_id must agree with the partition the service was built for. Reading it
        # and then ignoring it was the defect this check closes.
        if role_id is not None and role_id != self._namespace.subject_id:
            raise SubjectPartitionMismatch(
                f"role_id {role_id!r} does not match namespace subject "
                f"{self._namespace.subject_id!r}"
            )

    async def query_memory(self, query: str) -> str:
        if self._service is None or self._namespace is None:
            return ""
        # The utterance is deliberately NOT used as a recall text filter.
        #
        # Recall's text filter is a normalised substring match over canonical fields, and v0.4 has no
        # retrieval. A natural question like "我晚上吃什么好" shares no substring with
        # "food.dislike 香菜", so filtering on it returns nothing at all: the companion would look as
        # though she had forgotten, and no error would be raised anywhere. Ranking and retrieval are
        # v0.5 work. Until then the read path returns the character's confirmed memories and lets the
        # model do the relevance judgement, which is honest about what this layer can do.
        #
        # `query` is accepted for interface compatibility with the frozen base class and is the input
        # the future retrieval layer will use.
        records: List[Memory] = await self._service.recall(
            MemoryQuery(
                statuses=(CONFIRMED,),
                character_scope=self._namespace.character_scope,
            )
        )
        return "\n".join(render_memory(record) for record in records)

    async def save_memory(self, msgs, session_id=None):
        """Not implemented, and not stubbed.

        The frozen base class requires both halves of the interface, but this
        increment only establishes the read path: canonical memory -> the model's
        request context. Returning a no-op here would make the provider look
        complete and selectable in configuration while quietly discarding
        everything the user said, which is worse than an absent feature.

        The write path needs extraction plus an ingestion pipeline, and lands as
        its own increment.
        """
        raise NotImplementedError(
            "CanonicalMemoryProvider has no write path yet; the extraction and "
            "ingestion pipeline is a separate increment. Do not register this "
            "provider as a production memory provider until it exists."
        )
