"""The held-out acceptance evaluator must not reward fabricated citations, silence or duplicates."""
from copy import deepcopy
import importlib.util
from pathlib import Path

import pytest


SCRIPT = Path(__file__).resolve().parents[2] / "scripts/evaluate-knowledge.py"
loader = importlib.util.spec_from_file_location("evaluate_knowledge", SCRIPT)
ev = importlib.util.module_from_spec(loader)
loader.loader.exec_module(ev)


def examples():
    docs = ev.documents(ev.read(ev.FIXTURES / "documents.json"))
    gold = ev.read(ev.FIXTURES / "gold.json")
    prediction = {"format": 1, "corpus_digest": ev.digest(docs), "provider": "hand_fixture",
                  "model": "hand_fixture", "cases": {}}
    for case in docs["cases"]:
        spec = {s: [] for s in ev.IDS}
        spec["sources"] = []
        for d in case["documents"]:
            spec["sources"].append({"source_id": d["source_id"], "version": d["version"],
                                    "clauses": [{"clause_id": "c", "quote": d["text"],
                                                 "locator": f"字符[0,{len(d['text'])})"}]})
        for row in gold["cases"][case["id"]]["items"]:
            section = row["sections"][0]
            status = row.get("statuses", ["open" if section == "gaps" else "source_supported"])[0]
            item = {ev.IDS[section]: row["id"], "evidence": [
                {"source_id": s["source_id"], "clause_id": "c", "quote": s["text"]} for s in row["spans"]],
                "status": status}
            if section != "alignments":
                for ev_ref in item["evidence"]:
                    source = next(x for x in spec["sources"] if x["source_id"] == ev_ref["source_id"])
                    source["clauses"][0].setdefault(ev.BACKREF[section], []).append(row["id"])
            if section == "alignments":
                item.update(from_id="policy-5000", to_id="memo-8000")
                for a, side in zip(item["evidence"], ["from", "to"]):
                    a["side"] = side
                    d = next(d for d in case["documents"] if d["source_id"] == a["source_id"])
                    a["text"] = a.pop("quote")
                    a["start"] = d["text"].index(a["text"])
                    a["end"] = a["start"] + len(a["text"])
                    a["version"] = d["version"]
            for field, choices in row["fields"].items():
                node = item
                path = field.split(".")
                if path[-1] == "*":
                    node[path[0]] = [choices[0]]
                    continue
                for k in path[:-1]:
                    node = node.setdefault(k, {})
                node[path[-1]] = deepcopy(choices[0])
            spec[section].append(item)
        prediction["cases"][case["id"]] = spec
    return docs, prediction, gold


def test_one_to_one_accounting_and_explicit_abstention():
    docs, pred, gold = examples()
    assert ev.score(docs, pred, gold)["metrics"]["overall"]["correct"] == 5
    rules = pred["cases"]["stock"]["rules"]
    rules.append(dict(rules[0], rule_id="duplicate"))
    pred["cases"]["stock"]["sources"][0]["clauses"][0]["rule_ids"].append("duplicate")
    pred["cases"]["travel"]["gaps"].pop()
    metrics = ev.score(docs, pred, gold)["metrics"]
    assert metrics["assert"]["precision"] == .75
    assert metrics["abstain"]["recall"] == .5
    assert metrics["evidence_integrity"]["rate"] == 1
    assert metrics["overall"]["correct"] == 4


@pytest.mark.parametrize("mutation", ["partial", "range", "version", "foreign", "confident",
                                      "threshold", "date", "exception", "contradictory_result"])
def test_wrong_citations_and_wrong_semantics_do_not_earn_credit(mutation):
    docs, pred, gold = examples()
    spec = pred["cases"]["travel"]
    rule = spec["rules"][0]
    source = spec["sources"][0]
    if mutation == "partial":
        rule["evidence"][0]["quote"] = "5000元"
    if mutation == "range":
        source["clauses"][0]["locator"] = "字符[1,2)"
    if mutation == "version":
        source["version"] = "older"
    if mutation == "foreign":
        rule["evidence"][0]["source_id"] = "memo"
    if mutation == "confident":
        rule["status"] = "user_confirmed"
    if mutation == "threshold":
        rule["condition"]["comparator"] = "gt"
    if mutation == "date":
        rule["effective_from"] = "2026-03-02"
    if mutation == "exception":
        rule["exceptions"] = []
    if mutation == "contradictory_result":
        rule["results"].append("无需财务经理审批")
    report = ev.score(docs, pred, gold)
    assert "policy-5000" in report["cases"]["travel"]["missed"]


def test_external_review_can_judge_wording_while_evidence_and_status_still_gate_matches():
    docs, pred, gold = examples()
    question = pred["cases"]["travel"]["gaps"][0]
    question["question"] = "补签期限中的周期如何折算天数？"
    assert ev.score(docs, pred, gold)["metrics"]["overall"]["correct"] == 4
    blind = ev.blind(docs, pred)
    assert not ({"model", "provider", "gold_digest"} & blind.keys())
    review = {"format": 1, "reviewer": "reviewer", "prediction_digest": blind["prediction_digest"],
              "gold_digest": ev.digest(gold), "cases": {}}
    for key, case in blind["cases"].items():
        review["cases"][key] = {r["prediction_id"]: {"reason": "核对全部标注字段", "gold_id":
                                r["prediction_id"].split("/")[1]} for r in case["predictions"]}
    assert ev.score(docs, pred, gold, review)["metrics"]["overall"]["correct"] == 5
    question["evidence"].pop()
    review["prediction_digest"] = ev.digest(pred)
    assert ev.score(docs, pred, gold, review)["metrics"]["overall"]["correct"] == 4
    del review["cases"]["travel"]["gaps/time-unit"]
    with pytest.raises(ValueError, match="all in-scope predictions"):
        ev.score(docs, pred, gold, review)


def test_omission_has_no_precision_denominator_and_stale_corpus_fails():
    docs, pred, gold = examples()
    for spec in pred["cases"].values():
        for k in ev.IDS:
            spec[k] = []
    metrics = ev.score(docs, pred, gold)["metrics"]
    assert metrics["overall"]["precision"] is None and metrics["overall"]["recall"] == 0
    docs["cases"][0]["documents"][0]["text"] += "已修订"
    with pytest.raises(ValueError, match="different corpus version"):
        ev.score(docs, pred, gold)


def test_maximum_matching_assigns_ambiguous_predictions_and_verifies_span_offsets():
    assert ev.align({"p1": ["g1", "g2"], "p2": ["g1"]}) == {"g1": "p2", "g2": "p1"}
    docs, pred, gold = examples()
    row = pred["cases"]["travel"]["alignments"][0]
    row["evidence"][0]["start"] += 1
    metrics = ev.score(docs, pred, gold)["metrics"]
    assert metrics["evidence_integrity"]["valid"] == 4
    assert metrics["abstain"]["correct"] == 1


@pytest.mark.asyncio
async def test_extract_runs_worker_pages_and_policy_comparison_without_gold(monkeypatch):
    import json
    from app.llm import llm
    from app.tacit.extraction import PAGE_CHARS

    corpus = {"format": 1, "cases": [{"id": "blind-run", "scope": {"goal": "识别缺口", "in_scope": ["制度"]},
              "documents": [{"source_id": "D", "title": "资料", "version": "1",
                             "text": ("X" * 580 + "。") * (2 + PAGE_CHARS // 580)}]}]}
    observed = []
    async def complete(system, user):
        prompt = json.loads(user)
        observed.append(prompt)
        return json.dumps({"items": {"gaps": [
            {"gap_id": unit, "question": "需要补充的记录？", "impact": "待核验",
             "evidence": [{"unit": unit, "quote": quote[:30]}]}
            for unit, quote in prompt["passages"].items()]}, "excluded": []})
    monkeypatch.setattr(type(llm), "configured", property(lambda self: True))
    monkeypatch.setattr(llm, "complete", complete)
    result = await ev.extract(ev.documents(corpus))
    assert len(observed) >= 2 and all("gold" not in p for p in observed)
    assert result["cases"]["blind-run"]["sources"][0]["read_status"] == "read"
    assert result["cases"]["blind-run"]["alignments"] == []
    assert ev.blind(corpus, result)["cases"]["blind-run"]["predictions"]


def test_gold_is_bound_to_source_version_nested_types_are_distinct():
    docs, pred, gold = examples()
    gold["corpus_digest"] = "stale"
    with pytest.raises(ValueError, match="Gold belongs"):
        ev.score(docs, pred, gold)
    assert not ev.same_value({"value": [1]}, {"value": [True]})


def test_citation_must_link_to_the_claim_being_scored():
    docs, pred, gold = examples()
    spec = pred["cases"]["travel"]
    spec["sources"][0]["clauses"][0]["rule_ids"] = ["unrelated"]
    details = ev.score(docs, pred, gold)["cases"]["travel"]
    assert set(details["invalid_evidence"]) == {"rules/policy-5000", "alignments/precedence"}
