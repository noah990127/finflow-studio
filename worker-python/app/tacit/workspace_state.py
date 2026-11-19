"""Source versions, read coverage and human-supplied records in the project workspace."""
import hashlib
import json
from datetime import datetime, timezone
from uuid import uuid4

from .spec import empty_spec

FACT_SECTIONS = ("rules", "processes", "data_objects", "data_tables")
DOWNSTREAM = ("flows", "candidates", "cqs", "walkthrough")


def digest(value):
    raw = json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"), allow_nan=False)
    return hashlib.sha256(raw.encode()).hexdigest()


def new_state():
    return {"spec": empty_spec(), "source_versions": {}, "coverage": {}, "extraction": {},
            "user_sources": [], "interviews": {}, "artifacts": {}, "issues": []}


def required_string(payload, field):
    value = payload.get(field)
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"请填写 {field}")
    return value.strip()


def merge_ranges(ranges, start, end):
    merged = []
    for left, right in sorted([*ranges, [start, end]]):
        if merged and left <= merged[-1][1]:
            merged[-1][1] = max(merged[-1][1], right)
        elif right > left:
            merged.append([left, right])
    return merged


def invalidate(state, names=DOWNSTREAM):
    for name in names:
        artifact = state["artifacts"].get(name)
        if artifact:
            artifact["status"] = "stale"


def changed_spec(state):
    state["spec"]["completion"] = "draft"
    invalidate(state, (*DOWNSTREAM, "business_report", "gap_report"))


def stamp(source):
    return digest([str(source["version"]), source.get("digest") or digest(source["text"]),
                   bool(source.get("incomplete")), source.get("reason", "")])


def sources_by_id(state, catalog):
    # Only the command store can issue a user source or confirmation event.
    rows = [s for s in catalog if s["source_type"] != "user"] + state["user_sources"]
    return {s["source_id"]: dict(s) for s in rows}


def sync_sources(state, sources):
    versions = {key: stamp(s) for key, s in sources.items()}
    previous = state["source_versions"]
    changed = {key for key in previous.keys() | versions.keys() if previous.get(key) != versions.get(key)}
    rows = {s["source_id"]: s for s in state["spec"]["sources"]}
    if changed:
        changed_spec(state)
        for key in changed:
            state["coverage"].pop(key, None)
            state["extraction"].pop(key, None)
            rows.pop(key, None)
        # Source text can be superseded after a reviewer signed. Preserve the dated statement
        # for inspection but invalidate its endorsement and ask for an explicit new confirmation.
        if any(key in previous for key in changed):
            for note in state["user_sources"]:
                if note.get("confirmed_items"):
                    note["confirmed_items"] = {}
                    sources[note["source_id"]]["confirmed_items"] = {}
            for gap in state["spec"]["gaps"]:
                if gap.get("status") == "resolved":
                    gap.update(status="open", resolution="")
            for section in FACT_SECTIONS:
                for item in state["spec"][section]:
                    if item.get("status") == "user_confirmed":
                        item["status"] = "proposed"
    state["source_versions"] = versions
    for key, s in sources.items():
        length = len(s["text"])
        ranges = state["coverage"].get(key, [])
        read = "read" if length and (s["source_type"] == "user" or ranges == [[0, length]]) else (
            "partial" if ranges else "unread")
        s["read_status"] = read
        row = rows.setdefault(key, {"source_id": key, "disposition": "included", "clauses": [],
                                    "content_kind": "unspecified", "scope_reason": ""})
        row.update(title=s["title"], source_type=s["source_type"], version=str(s["version"]), read_status=read)
    state["spec"]["sources"] = list(rows.values())
    for table in state["spec"]["data_tables"]:
        referenced = {e["source_id"] for e in table.get("evidence", [])}
        statuses = {sources.get(key, {}).get("read_status", "unread") for key in referenced}
        table["read_status"] = ("read" if statuses == {"read"} else
                                "partial" if statuses.intersection({"read", "partial"}) else "unread")


def add_note(state, actor, text, title, confirmed=None):
    if len(text) > 100_000 or len(title) > 250:
        raise ValueError("单条访谈最多 100000 字符，标题最多 250 字符")
    note = {"source_id": "note-" + str(uuid4()), "title": title, "source_type": "user",
            "version": "1", "text": text, "author": actor, "created_at": datetime.now(timezone.utc).isoformat(),
            "confirmed_items": confirmed or {}}
    state["user_sources"].append(note)
    return note
