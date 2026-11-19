"""Portable reviewed business classes and relationships, independent of catalog join keys."""
import json
from collections import defaultdict

from .candidate_work import clauses_by_id, has_evidence
from .spec import CONFIRMED
from .spec_schema import Relationship
from .workspace_state import digest

RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
RDFS = "http://www.w3.org/2000/01/rdf-schema#"
OWL = "http://www.w3.org/2002/07/owl#"
PROV = "http://www.w3.org/ns/prov#"
DCT = "http://purl.org/dc/terms/"
FF = "urn:finflow:vocab:"


def business_ontology(project_id, snapshot, objects, spec):
    """Use absolute IRIs and quoted UTF-8 literals (the N-Triples subset of Turtle).

    Only controlled vocabulary and hashed project-local identities enter IRI tokens.
    Business labels, identifiers, quotes and source locators are always literal values.
    """
    base = "urn:finflow:project:" + digest(project_id)
    triples = set()

    def link(subject, predicate, iri):
        triples.add(f"<{subject}> <{predicate}> <{iri}> .")

    def literal(subject, predicate, value):
        encoded = json.dumps(value, ensure_ascii=False)
        triples.add(f"<{subject}> <{predicate}> {encoded} .")

    sources = {s["source_id"]: s for s in spec.get("sources", []) if s.get("disposition") != "excluded"}
    locators = {(sid, c["clause_id"]): c.get("locator", "") for sid, s in sources.items()
                for c in s.get("clauses", []) if c.get("coverage") != "out_of_scope"}
    clauses = clauses_by_id(spec)
    grounded = {(sid, c["clause_id"]) for sid, s in sources.items() if s.get("read_status") == "read"
                for c in s.get("clauses", []) if c.get("coverage") == "extracted"}
    grounded_clauses = {ref: clauses[ref] for ref in grounded}
    confirmed = {obj["object_id"]: [Relationship.model_validate(r).model_dump()
                                    for r in obj.get("relationships", []) if r.get("status") == "user_confirmed"]
                 for obj in spec.get("data_objects", []) if obj.get("status") == "user_confirmed"}

    def provenance(iri, evidence):
        for ev in evidence:
            sid, clause, quote = ev["source_id"], ev["clause_id"], ev["quote"]
            source = sources[sid]
            version = source.get("version", "")
            source_iri = base + ":source:" + digest([sid, version])
            citation = base + ":citation:" + digest([sid, version, clause, quote])
            link(iri, PROV + "wasDerivedFrom", citation)
            link(citation, RDF + "type", PROV + "Entity")
            link(citation, PROV + "wasDerivedFrom", source_iri)
            literal(citation, PROV + "value", quote)
            literal(citation, FF + "clauseId", clause)
            literal(citation, FF + "sourceLocator", locators[(sid, clause)])
            link(source_iri, RDF + "type", PROV + "Entity")
            literal(source_iri, DCT + "identifier", sid)
            literal(source_iri, DCT + "title", source.get("title", ""))
            literal(source_iri, FF + "sourceVersion", version)

    ontology = base + ":ontology"
    link(ontology, RDF + "type", OWL + "Ontology")
    link(ontology, OWL + "versionIRI", ontology + ":revision:" + snapshot)
    literal(ontology, DCT + "title", spec["scope"]["goal"])
    link(FF + "cardinality", RDF + "type", OWL + "AnnotationProperty")

    class_iris = {}
    by_object_id = defaultdict(list)
    for row, definition in objects:
        cid = row["candidate_id"]
        iri = base + ":class:" + digest(cid)
        class_iris[cid] = iri
        by_object_id[definition.get("object_id") or row["key"]].append(cid)
        link(iri, RDF + "type", OWL + "Class")
        literal(iri, RDFS + "label", row["name"])
        literal(iri, DCT + "identifier", cid)
        provenance(iri, row["evidence"])

    relationships = []
    trusted = defaultdict(set)
    for row, definition in objects:
        source = row["candidate_id"]
        for rel in definition.get("relationships", []):
            name, target = rel["name"], rel["target_object_id"]
            matches = by_object_id.get(target, [])
            target_id = matches[0] if len(matches) == 1 else None
            reason = ""
            if rel["status"] not in CONFIRMED or not name.strip() or rel["cardinality"] == "unresolved":
                reason = "关系名称、事实状态或基数仍待确认"
            elif rel["status"] == "user_confirmed" and rel not in confirmed.get(definition.get("object_id"), []):
                reason = "当前台账缺少针对该关系的人工确认记录"
            elif not has_evidence(rel.get("evidence"), grounded_clauses):
                reason = "关系证据无法定位到本版范围内的原文条款"
            elif not target_id:
                reason = "目标在已审核对象中缺失或无法唯一定位"
            if not reason:
                trusted[source, name, target_id].add(rel["cardinality"])
            relationships.append({"source_candidate_id": source, "target_candidate_id": target_id, "target_object_id": target,
                                  "name": name, "cardinality": rel["cardinality"], "fact_status": rel["status"],
                                  "evidence": rel["evidence"], "reason": reason})
    properties = set()
    for rel in relationships:
        source, target, name = rel["source_candidate_id"], rel["target_candidate_id"], rel["name"]
        ident = (source, name, target)
        if not rel["reason"] and len(trusted[ident]) > 1:
            rel["reason"] = "同名关系对同一目标的基数存在冲突"
        if rel["reason"]:
            rel.update(mapping_status="unmapped", destination="manifest_only", property_iri=None)
            continue
        iri = base + ":relation:" + digest(ident)
        rel.update(mapping_status="mapped", destination="ontology_turtle", property_iri=iri)
        properties.add(iri)
        link(iri, RDF + "type", OWL + "ObjectProperty")
        literal(iri, RDFS + "label", name)
        link(iri, RDFS + "domain", class_iris[source])
        link(iri, RDFS + "range", class_iris[target])
        literal(iri, FF + "cardinality", rel["cardinality"])
        provenance(iri, rel["evidence"])
    return "\n".join(sorted(triples)) + "\n", relationships, class_iris, len(properties)
