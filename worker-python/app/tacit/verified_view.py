"""Keep unsupported draft claims editable while suppressing them in derived expert views."""
import re
from copy import deepcopy

SOURCE_PATH = re.compile(r"^sources\[(\d+)\](?:\.clauses\[(\d+)\])?")
FACT_PATH = re.compile(r"^(rules|processes|data_objects|data_tables|gaps)\[(\d+)\]")
INVALID_EVIDENCE = {
    "unknown_source", "source_type_mismatch", "source_version_mismatch", "source_title_mismatch",
    "source_unread", "source_incomplete", "clause_identity", "clause_evidence", "fabricated_quote",
    "invalid_locator", "unclassified_clause", "excluded_extraction", "invalid_evidence",
    "invalid_confirmation", "missing_evidence", "excluded_source_used", "coverage_without_evidence",
    "unsupported_resolution",
}


def expert_view(spec, issues):
    if not issues:
        return spec
    view = deepcopy(spec)
    invalid_sources, invalid_clauses, invalid_items = set(), set(), set()
    for issue in issues:
        if issue["code"] not in INVALID_EVIDENCE:
            continue
        match = SOURCE_PATH.match(issue["path"])
        if match:
            source = spec["sources"][int(match[1])]
            if match[2] is None:
                invalid_sources.add(source["source_id"])
            else:
                clause = source["clauses"][int(match[2])]
                invalid_clauses.add((source["source_id"], clause["clause_id"]))
        match = FACT_PATH.match(issue["path"])
        if match:
            invalid_items.add((match[1], int(match[2])))

    for section in ("rules", "processes", "data_objects", "data_tables", "gaps"):
        for index, item in enumerate(view[section]):
            ev = item.get("evidence", [])
            verified = [e for e in ev if e["source_id"] not in invalid_sources and
                        (e["source_id"], e["clause_id"]) not in invalid_clauses]
            if len(verified) == len(ev) and (section, index) not in invalid_items:
                continue
            item["evidence"] = [] if (section, index) in invalid_items else verified
            if item.get("status") in ("source_supported", "user_confirmed"):
                item["status"] = "unresolved"
            elif section == "gaps" and item.get("status") == "resolved":
                item["status"] = "open"
                item["resolution"] = ""
    return view
