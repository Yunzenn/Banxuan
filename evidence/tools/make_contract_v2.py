"""Generate evidence/contracts/canonical-memory-v2.json from v1 plus the edit cases.

v1 stays byte-for-byte unchanged: it is a historical contract that has been through mutation
falsification and is enforced by both languages in required CI. v2 is a full snapshot rather than a
runtime `extends`, because a loader that inherits contracts would add versioning semantics to save a few
dozen lines of JSON.

Run:  python .tools/make_contract_v2.py
"""

import json
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
CONTRACTS = REPO / "evidence" / "contracts"
V1 = CONTRACTS / "canonical-memory-v1.json"
V2 = CONTRACTS / "canonical-memory-v2.json"

EARLY = "2026-09-27T10:00:00Z"
EDITED = "2026-09-28T10:00:00Z"
AT = "2026-09-26T13:00:00Z"

PROFILE_ORIGINAL = {
    "id": "m1",
    "type": "PROFILE",
    "status": "CONFIRMED",
    "attribute": "food.dislike",
    "value": "香菜",
    "recordedAt": EARLY,
    "excerpt": "我不喜欢香菜",
}

# The audit envelope a real correction must write. Compared explicitly per case rather than folded into
# every record snapshot, so v1's comparisons stay exactly as they were.
USER_EDIT_AUDIT = {
    "source": "USER_EDIT",
    "importance": "NORMAL",
    "recordedAt": EDITED,
    "sessionId": None,
    "messageId": None,
    "excerpt": "",
    "extractor": "user-edit-v1",
}

EDIT_CASES = [
    {
        "name": "edit_unknown_id",
        "initial": [],
        "operations": [{"op": "edit", "id": "missing",
                        "edit": {"type": "PROFILE", "editedAt": EDITED,
                                 "attribute": "food.dislike", "value": "芹菜"}}],
        "expect": {"error": "MEMORY_NOT_FOUND"},
    },
    {
        "name": "edit_rejected_is_refused_and_changes_nothing",
        "initial": [{"id": "m1", "type": "PROFILE", "status": "REJECTED",
                     "attribute": "food.dislike", "value": "香菜",
                     "recordedAt": EARLY, "excerpt": "我不喜欢香菜"}],
        "operations": [{"op": "edit", "id": "m1",
                        "edit": {"type": "PROFILE", "editedAt": EDITED,
                                 "attribute": "food.dislike", "value": "芹菜"}}],
        "expect": {
            "error": "MEMORY_NOT_EDITABLE",
            "store": [{"id": "m1", "type": "PROFILE", "status": "REJECTED",
                       "attribute": "food.dislike", "value": "香菜",
                       "recordedAt": EARLY, "excerpt": "我不喜欢香菜"}],
        },
    },
    {
        "name": "edit_wrong_type_is_refused",
        "initial": [PROFILE_ORIGINAL],
        "operations": [{"op": "edit", "id": "m1",
                        "edit": {"type": "EVENT", "editedAt": EDITED,
                                 "title": "去医院", "scheduledFor": AT}}],
        "expect": {
            "error": "MEMORY_TYPE_MISMATCH",
            "store": [PROFILE_ORIGINAL],
        },
    },
    {
        "name": "edit_unchanged_confirmed_keeps_the_original_provenance",
        "initial": [PROFILE_ORIGINAL],
        "operations": [{"op": "edit", "id": "m1",
                        "edit": {"type": "PROFILE", "editedAt": EDITED,
                                 "attribute": "food.dislike", "value": "香菜"}}],
        "expect": {
            "editOutcome": "unchanged",
            "store": [PROFILE_ORIGINAL],
            # A Save pressed without changing anything must not relabel the fact as a user edit.
            "audit": {"id": "m1", "source": "CONVERSATION", "importance": "NORMAL",
                      "recordedAt": EARLY, "sessionId": None, "messageId": None,
                      "excerpt": "我不喜欢香菜", "extractor": "canonical-memory-v1"},
        },
    },
    {
        "name": "edit_staged_stays_staged_and_becomes_a_user_edit",
        "initial": [{"id": "s1", "type": "PROFILE", "status": "STAGED",
                     "attribute": "food.dislike", "value": "香菜",
                     "recordedAt": EARLY, "excerpt": "我不喜欢香菜"}],
        "operations": [
            {"op": "edit", "id": "s1",
             "edit": {"type": "PROFILE", "editedAt": EDITED,
                      "attribute": "food.dislike", "value": "芹菜"}},
            {"op": "recall", "query": {}},
        ],
        "expect": {
            "editOutcome": "updated",
            # Editing is not confirming: the candidate is still invisible to the conversation.
            "recallAfter": [],
            "store": [{"id": "s1", "type": "PROFILE", "status": "STAGED",
                       "attribute": "food.dislike", "value": "芹菜",
                       "recordedAt": EDITED, "excerpt": ""}],
            "audit": dict(USER_EDIT_AUDIT, id="s1"),
        },
    },
    {
        "name": "edit_confirmed_same_identity_updates_in_place",
        "initial": [PROFILE_ORIGINAL],
        "operations": [
            {"op": "edit", "id": "m1",
             "edit": {"type": "PROFILE", "editedAt": EDITED,
                      "attribute": "food.dislike", "value": "芹菜"}},
            {"op": "recall", "query": {}},
        ],
        "expect": {
            "editOutcome": "updated",
            "recallAfter": ["m1"],
            "store": [{"id": "m1", "type": "PROFILE", "status": "CONFIRMED",
                       "attribute": "food.dislike", "value": "芹菜",
                       "recordedAt": EDITED, "excerpt": ""}],
            "audit": dict(USER_EDIT_AUDIT, id="m1"),
        },
    },
    {
        "name": "edit_confirmed_moves_the_identity_and_keeps_the_id",
        "initial": [PROFILE_ORIGINAL],
        "operations": [{"op": "edit", "id": "m1",
                        "edit": {"type": "PROFILE", "editedAt": EDITED,
                                 "attribute": "food.like", "value": "香菜"}}],
        "expect": {
            "editOutcome": "updated",
            # One record, moved. Not a second record, and not a new id.
            "store": [{"id": "m1", "type": "PROFILE", "status": "CONFIRMED",
                       "attribute": "food.like", "value": "香菜",
                       "recordedAt": EDITED, "excerpt": ""}],
            "audit": dict(USER_EDIT_AUDIT, id="m1"),
        },
    },
    {
        "name": "edit_confirmed_into_an_existing_identity_is_refused_untouched",
        "initial": [
            PROFILE_ORIGINAL,
            {"id": "m2", "type": "PROFILE", "status": "CONFIRMED",
             "attribute": "food.like", "value": "芹菜",
             "recordedAt": EARLY, "excerpt": "我爱吃芹菜"},
        ],
        "operations": [{"op": "edit", "id": "m1",
                        "edit": {"type": "PROFILE", "editedAt": EDITED,
                                 "attribute": "food.like", "value": "香菜"}}],
        "expect": {
            "error": "MEMORY_IDENTITY_CONFLICT",
            # Neither record moved: merging would consume one id and overwrite the other.
            "store": [
                PROFILE_ORIGINAL,
                {"id": "m2", "type": "PROFILE", "status": "CONFIRMED",
                 "attribute": "food.like", "value": "芹菜",
                 "recordedAt": EARLY, "excerpt": "我爱吃芹菜"},
            ],
        },
    },
    {
        "name": "edit_staged_may_move_onto_a_confirmed_identity",
        "initial": [
            PROFILE_ORIGINAL,
            {"id": "s1", "type": "PROFILE", "status": "STAGED",
             "attribute": "food.dislike", "value": "芹菜",
             "recordedAt": EARLY, "excerpt": "我现在爱吃芹菜"},
        ],
        "operations": [{"op": "edit", "id": "s1",
                        "edit": {"type": "PROFILE", "editedAt": EDITED,
                                 "attribute": "food.like", "value": "芹菜"}}],
        "expect": {
            "editOutcome": "updated",
            # "One known fact plus one pending correction" is a legitimate state for a candidate.
            "store": [
                PROFILE_ORIGINAL,
                {"id": "s1", "type": "PROFILE", "status": "STAGED",
                 "attribute": "food.like", "value": "芹菜",
                 "recordedAt": EDITED, "excerpt": ""},
            ],
            "audit": dict(USER_EDIT_AUDIT, id="s1"),
        },
    },
    {
        "name": "event_time_edit_moves_the_identity",
        "initial": [{"id": "e1", "type": "EVENT", "status": "CONFIRMED",
                     "title": "去医院", "scheduledFor": "2026-10-05T07:00:00Z",
                     "importance": "HIGH", "recordedAt": EARLY, "excerpt": "下周三去医院"}],
        "operations": [{"op": "edit", "id": "e1",
                        "edit": {"type": "EVENT", "editedAt": EDITED,
                                 "title": "去医院",
                                 "scheduledFor": "2026-10-09T07:00:00Z",
                                 "location": "浙一"}}],
        "expect": {
            "editOutcome": "updated",
            "store": [{"id": "e1", "type": "EVENT", "status": "CONFIRMED",
                       "title": "去医院", "scheduledFor": "2026-10-09T07:00:00Z",
                       "location": "浙一",
                       "recordedAt": EDITED, "excerpt": ""}],
            "audit": dict(USER_EDIT_AUDIT, id="e1", importance="HIGH"),
        },
    },
    {
        "name": "episode_edit_updates_summary_and_emotion",
        "initial": [{"id": "p1", "type": "EPISODE", "status": "CONFIRMED",
                     "summary": "和室友吵架了", "occurredAt": AT,
                     "emotionalTone": "委屈", "relations": ["室友"],
                     "recordedAt": EARLY, "excerpt": "昨天和室友吵架了"}],
        "operations": [{"op": "edit", "id": "p1",
                        "edit": {"type": "EPISODE", "editedAt": EDITED,
                                 "summary": "和室友和好了", "occurredAt": AT,
                                 "emotionalTone": "轻松", "relations": ["室友"]}}],
        "expect": {
            "editOutcome": "updated",
            "store": [{"id": "p1", "type": "EPISODE", "status": "CONFIRMED",
                       "summary": "和室友和好了", "occurredAt": AT,
                       "emotionalTone": "轻松", "relations": ["室友"],
                       "recordedAt": EDITED, "excerpt": ""}],
            "audit": dict(USER_EDIT_AUDIT, id="p1"),
        },
    },
    {
        "name": "relation_edit_moves_the_identity_through_the_role",
        "initial": [{"id": "r1", "type": "RELATION", "status": "CONFIRMED",
                     "name": "小李", "role": "室友",
                     "recordedAt": EARLY, "excerpt": "小李是我室友"}],
        "operations": [{"op": "edit", "id": "r1",
                        "edit": {"type": "RELATION", "editedAt": EDITED,
                                 "name": "小李", "role": "同事", "note": "换工作了"}}],
        "expect": {
            "editOutcome": "updated",
            "store": [{"id": "r1", "type": "RELATION", "status": "CONFIRMED",
                       "name": "小李", "role": "同事", "note": "换工作了",
                       "recordedAt": EDITED, "excerpt": ""}],
            "audit": dict(USER_EDIT_AUDIT, id="r1"),
        },
    },
]


def main() -> None:
    v1 = json.loads(V1.read_text(encoding="utf-8"))
    v2 = {
        "contract": "canonical-memory",
        "version": 2,
        "description": [
            "Full snapshot of the canonical-memory semantic contract at version 2. Both the Kotlin",
            "executable spec and the Python server authority must satisfy every case here, and neither",
            "implementation is the source of truth.",
            "v1 remains byte-for-byte unchanged and keeps its own conformance run: it is a historical",
            "contract that has been mutation-falsified and enforced in CI. v2 is a snapshot rather than",
            "a runtime `extends`, because loader inheritance would add contract versioning semantics",
            "just to avoid repeating these cases.",
            "Version 2 adds edit: the rules for correcting an existing memory's content, including the",
            "audit envelope and what happens when a correction moves a memory's identity.",
        ],
        "errorCodes": [
            "MEMORY_NOT_FOUND",
            "MEMORY_NOT_CONFIRMED",
            "MEMORY_NOT_STAGEABLE",
            "INVALID_TRANSITION",
            "MEMORY_NOT_EDITABLE",
            "MEMORY_TYPE_MISMATCH",
            "MEMORY_IDENTITY_CONFLICT",
        ],
        "outcomes": v1["outcomes"],
        "cases": v1["cases"] + EDIT_CASES,
    }
    V2.write_text(json.dumps(v2, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {V2.relative_to(REPO)}: {len(v2['cases'])} cases "
          f"({len(v1['cases'])} from v1 + {len(EDIT_CASES)} edit)")


if __name__ == "__main__":
    main()
