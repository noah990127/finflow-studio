from copy import deepcopy

from rdflib import Graph, Literal, Namespace, URIRef
from rdflib.namespace import DCTERMS, OWL, PROV, RDF, RDFS

from app.tacit.ontology_model import build_ontology_model
from test_tacit_ontology_model import reviewed_procurement

FF = Namespace("urn:finflow:vocab:")


def associations(monkeypatch):
    s = reviewed_procurement(monkeypatch)
    order = next(r for r in s.state["artifacts"]["candidates"]["content"] if r["kind"] == "Object")
    src = s.state["spec"]["sources"][0]
    clause = next(c for c in src["clauses"] if "回执" in c["quote"])
    ev = [{"source_id": src["source_id"], "clause_id": clause["clause_id"], "quote": clause["quote"]}]
    receipt = {"key": "receipt", "kind": "Object", "process_id": "", "name": '采购回执 "A"\\档案\n🧾',
               "evidence": ev, "definition": {"description": "回执"}}
    name = '出具 "回执"\\关联\n归档 🧾'
    good = {"target_object_id": "receipt", "name": name, "status": "source_supported",
            "cardinality": "one_to_many", "evidence": ev}
    bad_quote = [{"source_id": src["source_id"], "clause_id": clause["clause_id"], "quote": "没有这样的条款"}]
    unsupported = [dict(good, name="关联待确认", status="proposed"), dict(good, name="目标未审核", target_object_id="outside"),
                   dict(good, name="缺少原文", evidence=bad_quote), dict(good, name="未经登记的确认", status="user_confirmed"),
                   dict(good, name="相互矛盾", cardinality="one_to_one"), dict(good, name="相互矛盾", cardinality="many_to_one")]
    patched = deepcopy(order["definition"])
    patched["data_objects"]["relationships"] = [good, *unsupported]
    s.post("edit_candidates", {"patches": [{"candidate_id": order["candidate_id"], "definition": patched}],
                               "extensions": [receipt]})
    s.post("submit_candidates")
    s.post("review_candidate", {"decision": "approved"})
    return s, order, receipt, good


def test_turtle_parses_as_supported_business_relations_with_source_provenance(monkeypatch):
    s, order, receipt, assoc = associations(monkeypatch)
    built = build_ontology_model(s.state, "project-购")
    assert built == build_ontology_model(s.state, "project-购")
    graph = Graph().parse(data=built["ontology_turtle"], format="turtle")
    # The export deliberately uses an N-Triples-compatible Turtle profile.
    assert set(graph) == set(Graph().parse(data=built["ontology_turtle"], format="nt"))
    src, = graph.subjects(RDFS.label, Literal(order["name"]))
    dst, = graph.subjects(RDFS.label, Literal(receipt["name"]))
    prop, = graph.subjects(RDF.type, OWL.ObjectProperty)
    assert set(graph.subjects(RDF.type, OWL.Class)) == {src, dst}
    assert graph.value(prop, RDFS.domain) == src and graph.value(prop, RDFS.range) == dst
    assert graph.value(prop, RDFS.label) == Literal(assoc["name"])
    assert graph.value(prop, FF.cardinality) == Literal("one_to_many")
    assert not list(graph.subjects(RDF.type, OWL.Restriction))
    quote = graph.value(prop, PROV.wasDerivedFrom)
    source = graph.value(quote, PROV.wasDerivedFrom)
    assert graph.value(quote, PROV.value) == Literal(assoc["evidence"][0]["quote"])
    assert graph.value(quote, FF.clauseId) == Literal(assoc["evidence"][0]["clause_id"])
    assert graph.value(quote, FF.sourceLocator)
    assert graph.value(source, DCTERMS.identifier) == Literal("upload")
    assert graph.value(source, FF.sourceVersion) == Literal("1")
    rows = built["manifest"]["relationships"]
    mapped, = [r for r in rows if r["mapping_status"] == "mapped"]
    assert mapped["destination"] == "ontology_turtle" and URIRef(mapped["property_iri"]) == prop
    targets = {r["candidate_id"]: r["mapping"]["class_iri"] for r in built["manifest"]["candidates"] if r["kind"] == "Object"}
    assert URIRef(targets[mapped["source_candidate_id"]]) == src and URIRef(targets[mapped["target_candidate_id"]]) == dst
    assert len([r for r in rows if r["mapping_status"] == "unmapped"]) == 6
    assert all(r["reason"] and not r["property_iri"] for r in rows if r["destination"] == "manifest_only")
    assert built["counts"]["object_properties"] == 1
    assert built["model"]["semantic_layer"]["semantic_fks"] == built["model"]["data_layer"]["foreign_keys"] == []


def test_class_and_relation_ids_survive_new_review_and_target_removal_does_not_create_class(monkeypatch):
    s, order, receipt, _ = associations(monkeypatch)
    def graph(project):
        bundle = build_ontology_model(s.state, project)
        return Graph().parse(data=bundle["ontology_turtle"], format="turtle")
    before = graph("P")
    before_classes = set(before.subjects(RDF.type, OWL.Class))
    prop, = before.subjects(RDF.type, OWL.ObjectProperty)
    assert before_classes.isdisjoint(graph("other-project").subjects(RDF.type, OWL.Class))
    s.post("edit_candidates", {"patches": [{"candidate_id": order["candidate_id"], "name": "修订后的采购单"}]})
    s.post("submit_candidates")
    s.post("review_candidate", {"decision": "approved"})
    after = graph("P")
    assert set(after.subjects(RDF.type, OWL.Class)) == before_classes
    assert set(after.subjects(RDF.type, OWL.ObjectProperty)) == {prop}
    assert set(before.objects(None, OWL.versionIRI)) != set(after.objects(None, OWL.versionIRI))
    target = next(r for r in s.state["artifacts"]["candidates"]["content"] if r["key"] == receipt["key"])
    s.post("edit_candidates", {"remove_ids": [target["candidate_id"]]})
    s.post("submit_candidates")
    s.post("review_candidate", {"decision": "approved"})
    remaining = graph("P")
    assert len(set(remaining.subjects(RDF.type, OWL.Class))) == 1
    assert not list(remaining.subjects(RDF.type, OWL.ObjectProperty))
    unresolved = build_ontology_model(s.state, "P")["manifest"]["relationships"]
    assert all(r["target_candidate_id"] is None and r["destination"] == "manifest_only" for r in unresolved)
