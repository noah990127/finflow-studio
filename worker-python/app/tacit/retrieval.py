"""Project-scoped search. A derived record is searchable only through a current, resolvable citation."""
import json
import os
import re

import httpx

from .spec import CHAR_RANGE, _quote_range, item_digest, locate_quote, normalized
from .workspace_state import stamp

SECTIONS = {"assets", "cqs", "terminology", "ontology", "contract", "decisions"}
SOURCE_SECTION = {"asset": "assets", "cq": "cqs", "terminology": "terminology", "user": "decisions"}


def terms(text):
    tokens = set()
    # Split Han from neighbouring Latin text so Chinese phrases can match word fragments.
    for chunk in re.findall(r"[\u3400-\u9fff]+|[a-z0-9_]+", text.casefold()):
        if "\u3400" <= chunk[0] <= "\u9fff" and len(chunk) > 1:
            tokens.update(chunk[i:i + 2] for i in range(len(chunk) - 1))
        else:
            tokens.add(chunk)
    return tokens


def page_number(payload, field, default, maximum=None):
    value = payload.get(field, default)
    if type(value) is not int or value < (1 if field == "limit" else 0) or (maximum and value > maximum):
        raise ValueError(f"{field} 超出分页范围")
    return value


class SourceIndex:
    def __init__(self, state, sources):
        self.sources = {}
        self.clauses = {}
        self.exclusions = {}
        for row in state["spec"]["sources"]:
            sid = row["source_id"]
            source = sources.get(sid)
            if (not source or row.get("disposition") == "excluded"
                    or str(source["version"]) != str(row.get("version"))):
                continue
            self.sources[sid] = source
            for clause in row.get("clauses", []):
                if clause.get("coverage") != "out_of_scope":
                    self.clauses[sid, clause["clause_id"]] = clause
                else:
                    bounds = _quote_range(source, normalized(clause.get("quote", "")), clause.get("locator", ""))
                    self.exclusions.setdefault(sid, []).append(bounds or (0, len(source["text"])))
        self._bounds = {}
        self._stamps = {}
        self._anchors = {}

    def allowed(self, sid, start, end):
        return not any(start < b and end > a for a, b in self.exclusions.get(sid, []))

    def ranges(self, sid):
        cursor = 0
        for a, b in sorted(self.exclusions.get(sid, [])):
            if cursor < a:
                yield cursor, a
            cursor = max(cursor, b)
        if cursor < len(self.sources[sid]["text"]):
            yield cursor, len(self.sources[sid]["text"])

    def anchor(self, ev):
        key = tuple(ev.get(k) for k in ("source_id", "clause_id", "quote", "start", "end", "version", "source_stamp"))
        if key not in self._anchors:
            self._anchors[key] = self._resolve(ev)
        return self._anchors[key]

    def _resolve(self, ev):
        sid, cid, quote = ev.get("source_id"), ev.get("clause_id"), ev.get("quote")
        source = self.sources.get(sid)
        clause = self.clauses.get((sid, cid))
        if not source or not isinstance(quote, str) or not quote.strip():
            return None
        # Source-direct CQ records keep their complete locator even without a ledger clause.
        if "start" in ev or "end" in ev:
            left, right = ev.get("start"), ev.get("end")
            if sid not in self._stamps:
                self._stamps[sid] = stamp(source)
            if (str(ev.get("version")) != str(source["version"])
                    or ev.get("source_stamp", self._stamps[sid]) != self._stamps[sid]
                    or type(left) is not int or type(right) is not int
                    or not 0 <= left < right <= len(source["text"]) or source["text"][left:right] != quote
                    or not self.allowed(sid, left, right) or (cid and not clause)):
                return None
            anchor = self.span(sid, left, right)
            if cid:
                bounds = _quote_range(source, normalized(clause.get("quote", "")), clause.get("locator", ""))
                if not bounds or not bounds[0] <= left < right <= bounds[1]:
                    return None
                anchor["clause_id"] = cid
            return anchor
        if not clause:
            return None
        if (sid, cid) not in self._bounds:
            self._bounds[sid, cid] = _quote_range(source, normalized(clause.get("quote", "")), clause.get("locator", ""))
        bounds = self._bounds[sid, cid]
        if not bounds:
            return None
        left, right = bounds
        relative = locate_quote(source["text"][left:right], quote)
        if not (match := CHAR_RANGE.fullmatch(relative)):
            return None
        start, end = [left + int(x) for x in match.groups()]
        if self.allowed(sid, start, end):
            return {**self.span(sid, start, end), "clause_id": cid}
        return None

    def span(self, sid, start, end):
        source = self.sources[sid]
        if sid not in self._stamps:
            self._stamps[sid] = stamp(source)
        return {"source_id": sid, "version": str(source["version"]), "source_stamp": self._stamps[sid],
                "source_title": source["title"],
                "start": start, "end": end, "text": source["text"][start:end]}


def _art(state, section):
    artifact = state.get("artifacts", {}).get(section, {})
    rows = artifact.get("content", [])
    return rows if artifact.get("status") != "stale" and isinstance(rows, list) else []


def _plain(value):
    if isinstance(value, str):
        return value
    if isinstance(value, dict):
        return " ".join(_plain(v) for v in value.values())
    if isinstance(value, list):
        return " ".join(_plain(v) for v in value)
    return "" if value is None else str(value)


def _records(state):
    spec = state["spec"]
    for section, key, id_key in (("contract", "rules", "rule_id"), ("contract", "processes", "process_id"),
                                 ("ontology", "data_objects", "object_id"), ("ontology", "data_tables", "table_id"),
                                 ("contract", "gaps", "gap_id")):
        for row in spec[key]:
            if "excluded" in (row.get("disposition"), row.get("process_disposition")):
                continue
            title = row.get("name") or row.get("question") or row[id_key]
            body = {k: v for k, v in row.items() if k not in {"evidence", "relationships", "columns", "fields"}}
            yield section, row[id_key], title, _plain(body), row.get("evidence", [])
            # Stream individual fields so a wide table stays fully searchable.
            for pos, attr in enumerate(row.get("fields", []) + row.get("columns", [])):
                label = attr.get("business_name") or attr.get("name")
                if label:
                    yield "terminology", f"{row[id_key]}:field:{pos}", label, _plain(attr), row.get("evidence", [])
            for pos, rel in enumerate(row.get("relationships", [])):
                yield "ontology", f"{row[id_key]}:rel:{pos}", rel.get("name", title), _plain({
                    "name": rel.get("name"), "target": rel.get("target_object_id"),
                    "cardinality": rel.get("cardinality")}), rel.get("evidence", [])
    for row in _art(state, "cqs"):
        evidence = row.get("source", {}).get("evidence", []) or row.get("evidence", [])
        yield "cqs", row["cq_id"], row.get("cq_text", ""), _plain({
            k: row.get(k) for k in ("business_goal", "business_context", "expected_business_answer",
                                    "modeling_requirement", "technical_validation_question", "related_entities",
                                    "related_attributes", "related_relations", "cq_type", "review_status")}), evidence
    rules = {r["rule_id"]: r for r in spec["rules"]}
    for row in state.get("artifacts", {}).get("alignments", {}).get("content", []):
        if row.get("status") in ("merge", "conflict", "distinct"):
            anchors = [dict(a, quote=a["text"]) for a in row.get("evidence", [])]
            yield ("decisions", row["alignment_id"], "政策对齐审核", _plain([
                row.get("from_name"), row.get("to_name"), row["status"], row.get("comment")]), anchors)
            target = rules.get(row["to_id"])
            if (row["status"] == "merge" and target and target.get("process_disposition") != "excluded"
                    and row.get("merged_item_digest") == item_digest(target)):
                summary = f"{row['from_name']} · 已归并为 {target['name']}"
                yield "terminology", row["alignment_id"], row["from_name"], summary, anchors
                yield "contract", row["to_id"], target["name"], summary, anchors
    for row in _art(state, "candidates"):
        body = {k: v for k, v in row.items() if k in ("kind", "name", "review_status")}
        yield "ontology", row["candidate_id"], row.get("name", ""), _plain(body), row.get("evidence", [])


async def _light_chunks(query):
    endpoint = os.getenv("FINFLOW_TACIT_LIGHTRAG_URL", "").strip().rstrip("/")
    if not endpoint:
        return [], "disabled"
    headers = {"Accept": "application/json"}
    if token := os.getenv("FINFLOW_TACIT_LIGHTRAG_API_KEY"):
        headers["X-API-Key"] = token
    try:
        async with httpx.AsyncClient(timeout=5.0, follow_redirects=False) as client:
            async with client.stream("POST", endpoint + "/query/data", headers=headers,
                                     json={"query": query, "mode": "mix", "chunk_top_k": 20}) as response:
                response.raise_for_status()
                buf = bytearray()
                async for part in response.aiter_bytes():
                    if len(buf) + len(part) > 2_000_000:
                        return [], "unavailable"
                    buf.extend(part)
        chunks = json.loads(buf)["data"]["chunks"]
        if not isinstance(chunks, list):
            return [], "unavailable"
        return [x for x in chunks[:20] if isinstance(x, dict)], "available"
    except (httpx.HTTPError, httpx.InvalidURL, ValueError, TypeError, KeyError):
        return [], "unavailable"


async def search_context(state, payload, sources):
    query = payload.get("query", "")
    if not isinstance(query, str) or len(query) > 500:
        raise ValueError("检索词最多 500 字")
    query = query.strip()
    section = payload.get("section", "all")
    if section not in SECTIONS | {"all"}:
        raise ValueError("未知检索范围")
    offset, limit = page_number(payload, "offset", 0), page_number(payload, "limit", 20, 100)
    index = SourceIndex(state, sources)
    wanted, phrase = terms(query), query.casefold()
    hits = {}
    anchor_matches = {}

    def match(text):
        return phrase in text.casefold(), wanted & terms(text) if wanted else set()

    def emit(group, identity, title, summary, anchor, extra=0):
        if section not in ("all", group):
            return
        exact, common = match(title + " " + summary)
        # Do not match every column in a table merely because they cite the same table definition.
        if group != "terminology" or ":field:" not in identity:
            location = (anchor["source_id"], anchor["start"], anchor["end"])
            if location not in anchor_matches:
                anchor_matches[location] = match(anchor["text"])
            source_exact, source_terms = anchor_matches[location]
            exact, common = exact or source_exact, common | source_terms
        if not (exact or common or extra):
            return
        score = (60 if exact else 0) + round(30 * len(common) / max(len(wanted), 1)) + extra
        key = (group, identity, anchor["source_id"], anchor["start"], anchor["end"])
        if key not in hits or score > hits[key]["score"]:
            hits[key] = {"section": group, "id": identity, "title": title, "summary": summary[:900],
                         **anchor, "score": score}

    for sid, source in index.sources.items():
        group = SOURCE_SECTION[source["source_type"]]
        if section not in ("all", group):
            continue
        # Overlap covers phrases up to the request limit that cross a chunk boundary.
        for left, right in index.ranges(sid):
            for start in range(left, right, 1100):
                anchor = index.span(sid, start, min(start + 1600, right))
                emit(group, sid, source["title"], "", anchor)
                if anchor["end"] == right:
                    break

    for group, identity, title, summary, evidence in _records(state):
        if section not in ("all", group) or not evidence:
            continue
        # Discard the whole projection when any cited source is stale or no longer authorized.
        anchors = [index.anchor(ev) for ev in evidence]
        if all(anchors):
            for anchor in anchors:
                emit(group, identity, title, summary, anchor)

    chunks, provider = await _light_chunks(query) if query else ([], "not_queried")
    for chunk in chunks:
        quote = chunk.get("content")
        sid = chunk.get("source_id") or chunk.get("file_path")
        src = index.sources.get(sid) if isinstance(sid, str) else None
        if (not src or not isinstance(quote, str) or not quote.strip() or len(quote) > 32768
                or str(chunk.get("version", src["version"])) != str(src["version"])):
            continue
        start = src["text"].find(quote)
        if start >= 0 and index.allowed(sid, start, start + len(quote)):
            emit(SOURCE_SECTION[src["source_type"]], sid, src["title"], "",
                 index.span(sid, start, start + len(quote)), 35)
    ordered = sorted(hits.values(), key=lambda h: (-h["score"], h["section"], h["id"], h["source_id"], h["start"]))
    return {"items": ordered[offset:offset + limit], "total": len(ordered),
            "next_offset": offset + limit if offset + limit < len(ordered) else None,
            "providers": {"catalog": "available", "lightrag": provider}}
