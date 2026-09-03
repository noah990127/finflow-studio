---
name: research-and-evidence
description: Use for evidence gathering, knowledge search, external-source review, citation quality, conflicting sources, and provenance-first answers.
---
# Research And Evidence

## Load When
Use this skill when the task asks to find sources, verify claims, summarize evidence, explain why something happened, compare source reliability, or produce cited answers.

Do not load it for actions that only navigate or organize workspace items.

## Input Context
Determine research question, allowed source scope, project resources, external research policy, preferred authority level, language, deadline, and citation style.

## Tool Strategy
Use project knowledge first:

`search_knowledge -> read_resource/read_document -> search_resources`.

Only use external research tools when policy allows it. Prefer official filings, regulators, company releases, standards bodies, and primary data sources.

## Steps And Checkpoints
1. Break the question into evidence needs.
2. Search existing project knowledge.
3. Read selected evidence, not only search snippets.
4. Rank sources by authority, recency, and relevance.
5. Track conflicts, uncertainty, and missing evidence.
6. Feed cited evidence into final analysis or deliverable tools.

## Citation And Provenance
Each claim should reference a citation id, URL/page/section, source title, retrieval time, and whether the claim is quoted, summarized, calculated, or inferred.

## Failure Strategy
If source quality is weak, state that evidence is insufficient. If external access is disabled, explain that only project-local evidence was used.

## Human Confirmation Boundary
External write actions, saving research bundles, exporting reports, or adding knowledge sources require confirmation.

## Output Contract
Return answer, evidence table, confidence/limitations, unresolved questions, and provenance bundle.

## Example Tasks
- Find evidence explaining a revenue change.
- Verify whether a public policy affected this company.
- Summarize project knowledge with citations only from uploaded documents.
