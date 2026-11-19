#!/usr/bin/env python3
"""Extract, blind-review and score enterprise knowledge against independent expert labels."""
import argparse
import asyncio
from collections import Counter, deque
from hashlib import sha256
import json
from pathlib import Path
import random
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "scripts" / "fixtures" / "knowledge"
IDS = {"rules": "rule_id", "processes": "process_id", "data_tables": "table_id",
       "data_objects": "object_id", "gaps": "gap_id", "alignments": "alignment_id"}
BACKREF = {"rules": "rule_ids", "processes": "process_ids", "data_tables": "data_table_ids",
           "data_objects": "data_object_ids", "gaps": "gap_ids"}
LOCATOR = re.compile(r"字符\[(\d+),(\d+)\)")


def require(value, reason):
    if not value:
        raise ValueError(reason)


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def write(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")


def digest(data):
    return sha256(json.dumps(data, sort_keys=True, ensure_ascii=False,
                             separators=(",", ":"), allow_nan=False).encode()).hexdigest()


def index(rows, key):
    result = {}
    for row in rows:
        identity = row[key]
        require(isinstance(identity, str) and identity.strip() and identity not in result,
                f"Missing or duplicate identity for {key}")
        result[identity] = row
    return result


def documents(data):
    require(data["format"] == 1 and data["cases"], "Missing evaluation cases")
    for case in index(data["cases"], "id").values():
        require(case["scope"]["goal"] and case["scope"]["in_scope"], "Missing project scope")
        require(case["documents"], "Missing source documents")
        for doc in index(case["documents"], "source_id").values():
            require(all(isinstance(doc[k], str) and doc[k].strip() for k in ("text", "title", "version")),
                    "Each document needs text, title and version")
    return data


async def extract(data):
    sys.path.insert(0, str(ROOT / "worker-python"))
    from app.llm import llm
    from app.tacit.engine import execute
    require(llm.configured, "Configure the worker LLM provider before extracting")
    result = {"format": 1, "corpus_digest": digest(data), "provider": llm.provider,
              "model": llm.model, "cases": {}}
    for case in data["cases"]:
        catalog = [{**doc, "source_type": "asset"} for doc in case["documents"]]
        reply = await execute({}, "save_batch", {"section": "scope", "scope": case["scope"]}, catalog, "eval")
        for doc in catalog:
            offset = 0
            while offset is not None:
                reply = await execute(reply["state"], "extract", {"source_id": doc["source_id"],
                                      "offset": offset}, catalog, "eval")
                new_offset = reply["result"]["next_offset"]
                require(new_offset is None or new_offset > offset, "Non-advancing extraction cursor")
                offset = new_offset
        spec = reply["state"]["spec"]
        require(all(s["read_status"] == "read" for s in spec["sources"]), "Incomplete source coverage")
        offset = 0
        alignment_providers = []
        while offset is not None:
            reply = await execute(reply["state"], "align_policies", {"offset": offset}, catalog, "eval")
            alignment_providers.append(reply["result"]["semantic_provider"])
            next_offset = reply["result"]["next_offset"]
            require(next_offset is None or next_offset > offset, "Non-advancing comparison cursor")
            offset = next_offset
        result["cases"][case["id"]] = {**spec, "alignments": reply["state"]["artifacts"]["alignments"]["content"]}
        result.setdefault("alignment_providers", {})[case["id"]] = alignment_providers
    return result


def observations(spec, case):
    docs = index(case["documents"], "source_id")
    sources = index(spec["sources"], "source_id")
    clauses = {key: index(s["clauses"], "clause_id") for key, s in sources.items()}
    rows = {}
    for section, key in IDS.items():
        for identity, item in index(spec[section], key).items():
            if section == "alignments":
                item = {**item, "evidence": [{**a, "quote": a.get("text", "")} for a in item.get("evidence", [])]}
            refs = item.get("evidence", [])
            valid = bool(refs)
            if section == "alignments":
                valid &= (bool(item.get("from_id")) and bool(item.get("to_id")) and
                          item["from_id"] != item["to_id"] and {r.get("side") for r in refs} == {"from", "to"})
            for ref in refs:
                sid, cid, quote = ref.get("source_id"), ref.get("clause_id"), ref.get("quote")
                clause = clauses.get(sid, {}).get(cid, {})
                location = LOCATOR.fullmatch(clause.get("locator", ""))
                if sid not in docs or not location or not isinstance(quote, str) or not quote.strip():
                    valid = False
                    continue
                start, stop = map(int, location.groups())
                doc = docs[sid]
                owner_id = item.get(str(ref.get("side")) + "_id") if section == "alignments" else identity
                link = "rule_ids" if section == "alignments" else BACKREF[section]
                valid &= (sources[sid]["version"] == doc["version"] and
                          sources[sid].get("disposition", "included") != "excluded" and
                          clause.get("coverage") != "out_of_scope" and owner_id in clause.get(link, []) and
                          0 <= start < stop <= len(doc["text"]) and
                          doc["text"][start:stop] == clause["quote"] and quote in clause["quote"])
                if section == "alignments":
                    left, right = ref.get("start"), ref.get("end")
                    valid &= (ref.get("version") == doc["version"] and type(left) is int and type(right) is int
                              and start <= left < right <= stop and doc["text"][left:right] == quote)
            status = item.get("status", "open" if section == "gaps" else "unresolved")
            kind = {"source_supported": "assert", "unresolved": "abstain",
                    "conflicted": "abstain", "open": "abstain"}.get(status, "other")
            if section == "alignments" and status == "pending" and item.get("kind") == "possible_conflict":
                kind = "abstain"
            rows[section + "/" + identity] = {"item": item, "section": section, "status": status,
                                               "kind": kind, "evidence_valid": bool(valid)}
    return rows


def values(node, path):
    if not path:
        return [node]
    key, *rest = path
    if key == "*" and isinstance(node, list):
        return [v for entry in node for v in values(entry, rest)]
    if isinstance(node, dict) and key in node:
        return values(node[key], rest)
    return []


def same_value(a, b):
    # JSON booleans and integers compare equal in Python; they denote different facts.
    if type(a) in (int, float) and type(b) in (int, float):
        return a == b
    if type(a) is not type(b):
        return False
    if isinstance(a, list):
        return len(a) == len(b) and all(same_value(x, y) for x, y in zip(a, b))
    if isinstance(a, dict):
        return a.keys() == b.keys() and all(same_value(a[k], b[k]) for k in a)
    return a == b


def matches(pred, gold, *, check_fields):
    if (not pred["evidence_valid"] or pred["section"] not in gold["sections"] or
        pred["kind"] != gold["kind"] or
        ("statuses" in gold and pred["status"] not in gold["statuses"])):
        return False
    refs = pred["item"]["evidence"]
    for span in gold["spans"]:
        if not any(r["source_id"] == span["source_id"] and span["text"] in r["quote"] for r in refs):
            return False
    if not check_fields:
        return True
    return all(any(same_value(v, expected) for v in values(pred["item"], field.split("."))
                   for expected in choices) for field, choices in gold["fields"].items())


def align(edges):
    """Maximum one-to-one matching; a duplicate prediction cannot earn a second hit."""
    owners, assigned = {}, {}
    for prediction in edges:
        queue = deque([prediction])
        seen = {prediction}
        path = {}
        while queue:
            current = queue.popleft()
            found = False
            for candidate in edges[current]:
                if candidate in path:
                    continue
                path[candidate] = current
                owner = owners.get(candidate)
                if owner is None:
                    while candidate is not None:
                        current = path[candidate]
                        previous = assigned.get(current)
                        assigned[current] = candidate
                        owners[candidate] = current
                        candidate = previous
                    found = True
                    break
                if owner not in seen:
                    seen.add(owner)
                    queue.append(owner)
            if found:
                break
    return owners


def ratio(a, b):
    return a / b if b else None


def counts(tp, actual, expected):
    return {"correct": tp, "predicted": actual, "expected": expected, "precision": ratio(tp, actual),
            "recall": ratio(tp, expected), "f1": ratio(2*tp, actual+expected)}


def validate_run(data, prediction):
    require(prediction["format"] == 1 and prediction["corpus_digest"] == digest(data),
            "Predictions belong to a different corpus version")
    require(set(prediction["cases"]) == set(index(data["cases"], "id")), "Incomplete prediction cases")


def blind(data, prediction):
    validate_run(data, prediction)
    cases = {}
    for case in data["cases"]:
        key = case["id"]
        rows = observations(prediction["cases"][key], case)
        shuffled = list(rows.items())
        random.Random(digest(case)).shuffle(shuffled)
        cases[key] = {"documents": case["documents"], "predictions": [
            {"prediction_id": k, "section": row["section"], "fact": row["item"]}
            for k, row in shuffled]}
    # The reviewer gets no provider name, automatic score, field aliases or gold answers.
    return {"format": 1, "prediction_digest": digest(prediction), "cases": cases}


def score(data, prediction, labels, review=None):
    validate_run(data, prediction)
    require(labels.get("corpus_digest") == digest(data), "Gold belongs to a different corpus version")
    require(labels["format"] == 1 and set(labels["cases"]) == set(prediction["cases"]),
            "Gold case identifiers differ from the corpus")
    if review is not None:
        require(review["format"] == 1 and review["prediction_digest"] == digest(prediction)
                and review["gold_digest"] == digest(labels) and review["reviewer"].strip()
                and set(review["cases"]) == set(labels["cases"]), "Invalid or stale adjudication")
    totals = Counter()
    details = {}
    for case in data["cases"]:
        key = case["id"]
        gold = labels["cases"][key]
        docs = index(case["documents"], "source_id")
        sections = set(gold["sections"])
        require(sections and sections <= IDS.keys(), "Declare exhaustively labelled sections")
        rows = {k: v for k, v in observations(prediction["cases"][key], case).items()
                if v["section"] in sections}
        items = index(gold["items"], "id")
        for g in items.values():
            require(g["kind"] in {"assert", "abstain"} and g["sections"] and set(g["sections"]) <= sections
                    and g["fields"] and all(isinstance(v, list) and v for v in g["fields"].values())
                    and g["spans"] and all(s["source_id"] in docs and s["text"].strip() and
                    s["text"] in docs[s["source_id"]]["text"] for s in g["spans"]), "Invalid gold item " + g["id"])
        if review is None:
            edges = {pid: [gid for gid, g in items.items() if matches(row, g, check_fields=True)]
                     for pid, row in rows.items()}
        else:
            decisions = review["cases"][key]
            require(set(decisions) == set(rows), "Adjudicate all in-scope predictions, including false positives")
            edges = {}
            for pid, decision in decisions.items():
                gid = decision["gold_id"]
                require(decision["reason"].strip() and (gid is None or gid in items),
                        "Provide a reason and a valid gold ID or null")
                edges[pid] = [gid] if gid is not None and matches(rows[pid], items[gid], check_fields=False) else []
        matched = align(edges)
        for kind in ("assert", "abstain", "other"):
            totals[kind + "_pred"] += sum(row["kind"] == kind for row in rows.values())
            totals[kind + "_gold"] += sum(g["kind"] == kind for g in items.values())
            totals[kind + "_tp"] += sum(items[gid]["kind"] == kind for gid in matched)
        totals["evidence"] += sum(p["evidence_valid"] for p in rows.values())
        totals["pred"] += len(rows)
        totals["gold"] += len(items)
        totals["tp"] += len(matched)
        details[key] = {"matched": matched, "missed": sorted(set(items) - matched.keys()),
                        "unmatched_predictions": sorted(set(rows) - set(matched.values())),
                        "invalid_evidence": [k for k, v in rows.items() if not v["evidence_valid"]]}
    measures = {k: counts(totals[k+"_tp"], totals[k+"_pred"], totals[k+"_gold"])
                for k in ("assert", "abstain")}
    measures["overall"] = counts(totals["tp"], totals["pred"], totals["gold"])
    measures["evidence_integrity"] = {"valid": totals["evidence"], "predicted": totals["pred"],
                                      "rate": ratio(totals["evidence"], totals["pred"])}
    return {"scoring": "expert_adjudicated" if review is not None else "exact_labeled_fields",
            "model": prediction["model"], "provider": prediction["provider"], "metrics": measures,
            "cases": details, "gold_digest": digest(labels), "prediction_digest": digest(prediction)}


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    commands = cli.add_subparsers(dest="command", required=True)
    for command in ("fingerprint", "extract", "blind", "score"):
        p = commands.add_parser(command)
        p.add_argument("--documents", type=Path, default=FIXTURES / "documents.json")
        if command == "fingerprint":
            continue
        p.add_argument("--output", type=Path, required=True)
        if command != "extract":
            p.add_argument("--predictions", type=Path, required=True)
        if command == "blind":
            p.add_argument("--review-template", type=Path,
                           help="Optional blank adjudication file for the evaluation coordinator")
        if command in ("blind", "score"):
            p.add_argument("--gold", type=Path, default=FIXTURES / "gold.json")
        if command == "score":
            p.add_argument("--adjudication", type=Path)
            p.add_argument("--require", action="append", default=[], metavar="METRIC=MIN",
                           help="E.g. --require overall.recall=0.90 --require abstain.recall=1")
    args = cli.parse_args()
    data = documents(read(args.documents))
    if args.command == "fingerprint":
        print(digest(data))
    elif args.command == "extract":
        write(args.output, asyncio.run(extract(data)))
    elif args.command == "blind":
        prediction = read(args.predictions)
        packet = blind(data, prediction)
        write(args.output, packet)
        if args.review_template:
            labels = read(args.gold)
            require(labels.get("corpus_digest") == digest(data), "Gold belongs to a different corpus version")
            require(set(labels["cases"]) == set(packet["cases"]), "Gold case identifiers differ from the corpus")
            template = {"format": 1, "reviewer": "", "prediction_digest": digest(prediction),
                        "gold_digest": digest(labels), "cases": {
                            key: {r["prediction_id"]: {"gold_id": None, "reason": ""}
                                  for r in case["predictions"] if r["section"] in labels["cases"][key]["sections"]}
                            for key, case in packet["cases"].items()}}
            write(args.review_template, template)
    else:
        pred = read(args.predictions)
        result = score(data, pred, read(args.gold), read(args.adjudication) if args.adjudication else None)
        failed = []
        for gate in args.require:
            metric, threshold = gate.split("=")
            category, measure = metric.split(".")
            require(measure in {"precision", "recall", "f1", "rate"} and 0 <= float(threshold) <= 1,
                    "Unknown measure or invalid threshold")
            actual = result["metrics"][category][measure]
            if actual is None or actual < float(threshold):
                failed.append(gate)
        result["failed_gates"] = failed
        write(args.output, result)
        print(json.dumps(result["metrics"], ensure_ascii=False))
        return 2 if failed else 0
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, RuntimeError, OSError, KeyError) as exc:
        # Worker provider exceptions can include remote payloads in the exception chain.
        sys.exit(f"Evaluation failed ({type(exc).__name__}): {exc}")
