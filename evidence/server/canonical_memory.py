"""Canonical memory semantics - the server-side authority implementation.

This is the Python counterpart of the Kotlin executable spec in
``core-memory/DefaultMemoryGateway.kt``. The two must satisfy the same
language-neutral contract in ``evidence/contracts/canonical-memory-v1.json``.

Why this exists at all, rather than the watch owning the semantics: the canonical
store is server-side and the LLM is server-side, so a design where the Android
client performs de-duplication, lifecycle and scoping would make the companion's
memory depend on a phone being awake, and would turn every read-decide-write into
a non-atomic operation across the network. The Kotlin gateway therefore stays the
executable specification and the Android-side reference, and this module is the
authority.

Division of responsibility, copied deliberately from the Kotlin side:

    CanonicalMemoryService   all semantics: dedup, transitions, id stability, recall policy
    CanonicalMemoryStore     storage primitives only, no opinions

A third place that reimplements any of those rules would defeat the point of the
shared contract, so nothing else in the server patch may do so.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field, replace
from datetime import datetime, timezone
from typing import Dict, Iterable, List, Optional, Sequence, Tuple

PROFILE = "PROFILE"
EVENT = "EVENT"
EPISODE = "EPISODE"
RELATION = "RELATION"
MEMORY_TYPES = (PROFILE, EVENT, EPISODE, RELATION)

STAGED = "STAGED"
CONFIRMED = "CONFIRMED"
REJECTED = "REJECTED"
MEMORY_STATUSES = (STAGED, CONFIRMED, REJECTED)

# Error codes are language-neutral on purpose: the conformance contract compares these strings, never
# Kotlin or Python exception class names.
MEMORY_NOT_FOUND = "MEMORY_NOT_FOUND"
MEMORY_NOT_CONFIRMED = "MEMORY_NOT_CONFIRMED"
MEMORY_NOT_STAGEABLE = "MEMORY_NOT_STAGEABLE"
INVALID_TRANSITION = "INVALID_TRANSITION"

_TRAILING_PUNCTUATION = ".。．!！,，;；"
_WHITESPACE = re.compile(r"\s+")
_ATTRIBUTE_PADDING = re.compile(r"\s*\.\s*")
# A trailing run of whitespace and punctuation, stripped together. Stripping punctuation
# alone leaves the whitespace that preceded it, so "food. dislike 。" would fold to
# "food. dislike " and then to "food.dislike " - a different identity from "food.dislike",
# which is a duplicate memory produced by nothing but a stray space.
_TRAILING_NOISE = re.compile(r"[\s.。．!！,，;；]+$")
_UNDATED = "undated"


def normalize_fact(text: str) -> str:
    """Case/whitespace folded, trailing punctuation and whitespace stripped.

    Mirrors ``normalizeFact`` in CanonicalMemory.kt. The strip matters: an extractor
    emitting ``香菜。`` and one emitting ``香菜`` mean the same thing, and treating them
    as different facts is exactly how duplicate memories appear.
    """
    return _TRAILING_NOISE.sub("", _WHITESPACE.sub(" ", text.strip().lower()))


def normalize_attribute_path(path: str) -> str:
    """``normalize_fact`` plus folding of whitespace around the ``.`` separators.

    An attribute path is an identifier, not prose, so ``food. dislike`` and
    ``food.dislike`` are the same attribute. Free text keeps its internal spacing,
    which is why this is separate rather than a stronger ``normalize_fact``.
    """
    return _ATTRIBUTE_PADDING.sub(".", normalize_fact(path))


def parse_instant(text: str) -> datetime:
    value = text.strip()
    if value.endswith("Z"):
        value = value[:-1] + "+00:00"
    parsed = datetime.fromisoformat(value)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def canonical_instant(text: Optional[str]) -> Optional[str]:
    """Re-emits an instant the way Kotlin's ``Instant.toString()`` does.

    Both sides compare instant strings literally in the shared contract, so a
    fixture written as ``2026-09-27T10:00:00+08:00`` must come back as
    ``2026-09-27T02:00:00Z`` on both sides.
    """
    if text is None:
        return None
    parsed = parse_instant(text)
    base = parsed.strftime("%Y-%m-%dT%H:%M:%S")
    if parsed.microsecond:
        return f"{base}.{parsed.microsecond:06d}".rstrip("0") + "Z"
    return base + "Z"


@dataclass(frozen=True)
class Memory:
    """One typed record. Field layout mirrors the Kotlin sealed hierarchy's envelope."""

    id: str
    type: str
    status: str
    recorded_at: str
    character_scope: str = "xiaozhi"
    importance: str = "NORMAL"
    source: str = "CONVERSATION"
    excerpt: str = ""
    session_id: Optional[str] = None
    message_id: Optional[str] = None
    extractor: str = "canonical-memory-v1"

    # PROFILE
    attribute: Optional[str] = None
    value: Optional[str] = None

    # EVENT
    title: Optional[str] = None
    scheduled_for: Optional[str] = None
    location: Optional[str] = None

    # EPISODE
    summary: Optional[str] = None
    occurred_at: Optional[str] = None
    emotional_tone: Optional[str] = None
    relations: Tuple[str, ...] = ()

    # RELATION
    name: Optional[str] = None
    role: Optional[str] = None
    note: Optional[str] = None

    @property
    def identity(self) -> Tuple[str, str]:
        if self.type == PROFILE:
            return (PROFILE, normalize_attribute_path(self.attribute or ""))
        if self.type == EVENT:
            return (EVENT, normalize_fact(self.title or "") + "@" + (self.scheduled_for or _UNDATED))
        if self.type == EPISODE:
            return (EPISODE, normalize_fact(self.summary or "") + "@" + (self.occurred_at or ""))
        return (RELATION, normalize_fact(self.name or "") + "/" + normalize_fact(self.role or ""))

    @property
    def scoped_identity(self) -> Tuple[str, Tuple[str, str]]:
        """The de-duplication key. Never ``identity`` alone - see the model docs."""
        return (self.character_scope, self.identity)

    @property
    def content_fingerprint(self) -> str:
        """Everything that makes the content, identity included.

        ``identity`` cannot serve here: a PROFILE's identity deliberately excludes its
        value, so comparing identity would report a genuine correction as no change.
        """
        if self.type == PROFILE:
            return normalize_attribute_path(self.attribute or "") + "|" + normalize_fact(self.value or "")
        if self.type == EVENT:
            return self.identity[1] + "|" + normalize_fact(self.location or "")
        if self.type == EPISODE:
            relations = ",".join(sorted(normalize_fact(r) for r in self.relations))
            return self.identity[1] + "|" + normalize_fact(self.emotional_tone or "") + "|" + relations
        return self.identity[1] + "|" + normalize_fact(self.note or "")

    @property
    def windowed_at(self) -> Optional[str]:
        """The instant a time window applies to, or None for a timeless fact."""
        if self.type == EVENT:
            return self.scheduled_for
        if self.type == EPISODE:
            return self.occurred_at
        return None

    @property
    def timeline_at(self) -> str:
        if self.type == EVENT:
            return self.scheduled_for or self.recorded_at
        if self.type == EPISODE:
            return self.occurred_at or self.recorded_at
        return self.recorded_at

    @property
    def searchable_text(self) -> str:
        if self.type == PROFILE:
            return " ".join(filter(None, (self.attribute, self.value)))
        if self.type == EVENT:
            return " ".join(filter(None, (self.title, self.location)))
        if self.type == EPISODE:
            return " ".join(filter(None, (self.summary, self.emotional_tone, *self.relations)))
        return " ".join(filter(None, (self.name, self.role, self.note)))

    def with_status(self, status: str) -> "Memory":
        return replace(self, status=status)

    def with_id(self, memory_id: str) -> "Memory":
        return replace(self, id=memory_id)


@dataclass(frozen=True)
class MemoryQuery:
    text: Optional[str] = None
    types: Tuple[str, ...] = MEMORY_TYPES
    character_scope: Optional[str] = None
    # CONFIRMED-only by default, and that default is a safety property rather than a
    # convenience: a candidate the user has not accepted must not reach the conversation.
    statuses: Tuple[str, ...] = (CONFIRMED,)
    from_instant: Optional[str] = None
    to_instant: Optional[str] = None
    limit: int = 8


@dataclass(frozen=True)
class RememberOutcome:
    kind: str  # "created" | "updated" | "unchanged"
    memory: Memory


class MemoryError(Exception):
    code = "MEMORY_ERROR"

    def __init__(self, message: str):
        super().__init__(message)
        self.message = message


class MemoryNotFound(MemoryError):
    code = MEMORY_NOT_FOUND


class MemoryNotConfirmed(MemoryError):
    code = MEMORY_NOT_CONFIRMED


class MemoryNotStageable(MemoryError):
    code = MEMORY_NOT_STAGEABLE


class InvalidTransition(MemoryError):
    code = INVALID_TRANSITION


class SubjectPartitionMismatch(Exception):
    """A namespace and a service describing different subjects were used together.

    ``subject_id`` is the storage partition boundary, so a mismatch would read a
    different user's memories while every other check still passed. Refused rather
    than warned about, because a warning here is one overlooked log line away from a
    cross-user disclosure - and because an earlier version carried a ``subject_id``
    that nothing ever read, which is exactly how that happens.
    """


@dataclass(frozen=True)
class MemoryNamespace:
    """Who a memory belongs to: a subject, and a character within that subject.

    ``subject_id`` is the device today (the frozen server passes
    ``role_id=self.device_id``), and it is kept as its own field rather than
    concatenated with the character because it will become an account or user id
    later. Serialising the two into one key is a migration decision and belongs in a
    single versioned encoder when a substrate actually needs one, not spread through
    the code as string formatting.

    This lives here rather than in the Xiaozhi adapter because it is not a Xiaozhi
    concept: it is the partitioning of canonical memory, and keeping it in the
    adapter would make the isolation property untestable without a server checkout.
    """

    subject_id: str
    character_scope: str


class CanonicalMemoryStore:
    """Storage port. Primitives only: no dedup, no transitions, no recall policy."""

    def partition(self, subject_id: str) -> "CanonicalMemoryStore":
        """A view of this store holding one subject's records and nothing else.

        ``subject_id`` is the storage partition boundary, not a field on
        :class:`Memory`. That separation is deliberate: ``CanonicalMemory`` describes
        *what a memory is* and ``character_scope`` describes *which character knows
        it*; who the memory belongs to is an infrastructure concern one level below.
        A service is therefore constructed against exactly one partition and can
        never observe another subject's records at all.

        Without this boundary, one service backing two devices or two accounts would
        let them recall and dedupe against each other's memories whenever they
        happened to share a character scope - and nothing in the model would look
        wrong, because ``character_scope`` would be doing its job correctly.
        """
        raise NotImplementedError

    async def get_by_id(self, memory_id: str) -> Optional[Memory]:
        raise NotImplementedError

    async def find_all_by_scoped_identity(self, scoped_identity) -> List[Memory]:
        """Every record with this scoped identity, in any status.

        Plural on purpose: a fact may exist as a confirmed memory and as a pending
        change proposal at the same time, and collapsing that here would force a
        policy decision into the layer that must not have one.
        """
        raise NotImplementedError

    async def put(self, memory: Memory) -> None:
        raise NotImplementedError

    async def delete(self, memory_id: str) -> bool:
        raise NotImplementedError

    async def list(self) -> List[Memory]:
        raise NotImplementedError


class InMemoryCanonicalMemoryStore(CanonicalMemoryStore):
    """Process-local store, partitioned by subject.

    A substrate for tests and for running the semantics without a database. It is
    not the production persistence layer, and being process-local it must never be
    presented as one.

    Partitions share one backing map so that two subjects can be exercised against a
    single store instance, which is the only way the isolation property can actually
    be tested.
    """

    def __init__(self, backing: Optional[Dict[str, Dict[str, Memory]]] = None,
                 subject_id: str = "__unpartitioned__") -> None:
        self._backing = backing if backing is not None else {}
        self._subject_id = subject_id

    def partition(self, subject_id: str) -> "InMemoryCanonicalMemoryStore":
        return InMemoryCanonicalMemoryStore(self._backing, subject_id)

    def _bucket(self) -> Dict[str, Memory]:
        return self._backing.setdefault(self._subject_id, {})

    async def get_by_id(self, memory_id: str) -> Optional[Memory]:
        return self._bucket().get(memory_id)

    async def find_all_by_scoped_identity(self, scoped_identity) -> List[Memory]:
        return [r for r in self._bucket().values() if r.scoped_identity == scoped_identity]

    async def put(self, memory: Memory) -> None:
        self._bucket()[memory.id] = memory

    async def delete(self, memory_id: str) -> bool:
        return self._bucket().pop(memory_id, None) is not None

    async def list(self) -> List[Memory]:
        return list(self._bucket().values())


class CanonicalMemoryService:
    """The authority on what a memory means, for exactly one subject.

    Maintains two invariants rather than assuming them of the store:

    * at most one record per scoped identity may be CONFIRMED;
    * a record's id survives a correction, so a reference the user is holding still
      names the same fact afterwards.

    A service is bound to one ``subject_id`` at construction and works against that
    subject's store partition. ``character_scope`` on each record is a different
    axis: it says which character knows the fact, within one subject's memory. Two
    subjects that both use a character called ``xiaozhi`` are still two disjoint
    memories, and this confinement is what makes that true - it is not left to every
    caller to remember to filter.
    """

    def __init__(self, store: CanonicalMemoryStore, subject_id: str) -> None:
        self.subject_id = subject_id
        self._store = store.partition(subject_id)

    async def stage(self, records: Sequence[Memory]) -> List[Memory]:
        for record in records:
            if record.status != STAGED:
                raise MemoryNotStageable(
                    f"memory {record.id} is {record.status}, expected STAGED"
                )
        for record in records:
            await self._store.put(record)
        return list(records)

    async def confirm(self, memory_id: str) -> Memory:
        candidate = await self._store.get_by_id(memory_id)
        if candidate is None:
            raise MemoryNotFound(f"no memory with id {memory_id}")
        if candidate.status != STAGED:
            raise InvalidTransition(
                f"memory {memory_id} is {candidate.status} and cannot become {CONFIRMED}"
            )

        existing = await self._confirmed_for(candidate.scoped_identity)
        if existing is None:
            confirmed = candidate.with_status(CONFIRMED)
            await self._store.put(confirmed)
            return confirmed

        # Whether this changes anything depends on content, not on identity: a
        # PROFILE's identity excludes its value, so an identity test would report a
        # genuine correction as no change at all.
        if existing.content_fingerprint == candidate.content_fingerprint:
            # Confirming something already true. Drop the redundant proposal and leave
            # the confirmed record - provenance and recorded_at included - untouched.
            await self._store.delete(candidate.id)
            return existing

        corrected = candidate.with_id(existing.id).with_status(CONFIRMED)
        await self._store.put(corrected)
        await self._store.delete(candidate.id)
        return corrected

    async def reject(self, memory_id: str) -> Memory:
        candidate = await self._store.get_by_id(memory_id)
        if candidate is None:
            raise MemoryNotFound(f"no memory with id {memory_id}")
        if candidate.status != STAGED:
            raise InvalidTransition(
                f"memory {memory_id} is {candidate.status} and cannot become {REJECTED}"
            )
        rejected = candidate.with_status(REJECTED)
        await self._store.put(rejected)
        return rejected

    async def remember(self, memory: Memory) -> RememberOutcome:
        if memory.status != CONFIRMED:
            raise MemoryNotConfirmed(
                f"memory {memory.id} is {memory.status}, expected CONFIRMED"
            )

        existing = await self._confirmed_for(memory.scoped_identity)
        if existing is None:
            await self._store.put(memory)
            return RememberOutcome("created", memory)

        if existing.content_fingerprint == memory.content_fingerprint:
            return RememberOutcome("unchanged", existing)

        updated = memory.with_id(existing.id)
        await self._store.put(updated)
        return RememberOutcome("updated", updated)

    async def recall(self, query: MemoryQuery = MemoryQuery()) -> List[Memory]:
        kept = [
            record
            for record in await self._store.list()
            if record.status in query.statuses
            and record.type in query.types
            and (query.character_scope is None or record.character_scope == query.character_scope)
            and _within_window(record, query.from_instant, query.to_instant)
            and _matches_text(record, query.text)
        ]
        kept.sort(key=lambda r: parse_instant(r.timeline_at), reverse=True)
        return kept[: query.limit]

    async def list(
        self,
        statuses: Sequence[str] = MEMORY_STATUSES,
        character_scope: Optional[str] = None,
    ) -> List[Memory]:
        kept = [
            record
            for record in await self._store.list()
            if record.status in statuses
            and (character_scope is None or record.character_scope == character_scope)
        ]
        kept.sort(key=lambda r: parse_instant(r.recorded_at), reverse=True)
        return kept

    async def forget(self, memory_id: str) -> bool:
        return await self._store.delete(memory_id)

    async def _confirmed_for(self, scoped_identity) -> Optional[Memory]:
        matches = [
            record
            for record in await self._store.find_all_by_scoped_identity(scoped_identity)
            if record.status == CONFIRMED
        ]
        if not matches:
            return None
        # The invariant above means there is normally one. The defensive maximum keeps
        # the choice deterministic if a store ever holds records written elsewhere.
        return max(matches, key=lambda r: parse_instant(r.recorded_at))


def _within_window(record: Memory, from_instant: Optional[str], to_instant: Optional[str]) -> bool:
    if from_instant is None and to_instant is None:
        return True
    at = record.windowed_at
    if at is None:
        # A PROFILE or RELATION is a timeless fact: neither inside nor outside a window.
        return False
    moment = parse_instant(at)
    if from_instant is not None and moment < parse_instant(from_instant):
        return False
    if to_instant is not None and moment > parse_instant(to_instant):
        return False
    return True


def _matches_text(record: Memory, text: Optional[str]) -> bool:
    if text is None:
        return True
    needle = normalize_fact(text)
    return not needle or needle in normalize_fact(record.searchable_text)
