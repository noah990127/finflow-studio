"""Authenticated answers kept against a stable gap ID. Repeated answers never add corroborating evidence."""
from datetime import datetime, timezone

from .workspace_state import add_note, digest, required_string, sync_sources


def record_answer(state, sources, payload, actor):
    gap_id = required_string(payload, "gap_id")
    text = required_string(payload, "text")
    resolve = payload.get("resolve", False)
    if type(resolve) is not bool:
        raise ValueError("请选择补充材料或确认解决")
    gap = next((g for g in state["spec"]["gaps"] if g["gap_id"] == gap_id), None)
    if not gap or gap.get("status") == "resolved":
        raise ValueError("请选择尚未解决的问题")
    interview = state.setdefault("interviews", {}).setdefault(gap_id, {
        "turns": 0, "last_asked_at": "", "no_increment_count": 0, "paused": False, "answers": {},
    })
    interview["turns"] += 1
    interview["last_asked_at"] = datetime.now(timezone.utc).isoformat()
    signature = digest("".join(text.split()))
    note_id = interview["answers"].get(signature)
    if note_id in sources and not resolve:
        interview["no_increment_count"] += 1
        interview["paused"] = interview["no_increment_count"] >= 2
        return {"gap_id": gap_id, "decision": "no_increment", "source_id": note_id}
    interview.update(no_increment_count=0, paused=False)
    if note_id not in sources:
        note = add_note(state, actor, text, "缺口答复 · " + gap["question"][:150])
        note_id = note["source_id"]
        sources[note_id] = dict(note)
        sync_sources(state, sources)
        row = next(r for r in state["spec"]["sources"] if r["source_id"] == note_id)
        row["clauses"] = [{"clause_id": "answer", "locator": f"字符[0,{len(text)})", "quote": text,
                            "coverage": "gap", "gap_ids": [gap_id]}]
        gap.setdefault("evidence", []).append({"source_id": note_id, "clause_id": "answer", "quote": text})
        interview["answers"][signature] = note_id
    if resolve:
        gap.update(status="resolved", resolution=text)
    return {"gap_id": gap_id, "decision": "resolved" if resolve else "supplemented", "source_id": note_id}
