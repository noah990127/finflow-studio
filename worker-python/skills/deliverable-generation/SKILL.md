---
name: deliverable-generation
description: Route verified analysis into the right professional result format. Load the format-specific authoring skill before creating PPT, HTML slides, Word, PDF, interactive reports, Mermaid, or Excalidraw.
---
# Deliverable Generation

## Load When
Use this skill when the user asks for a report, slide, presentation, document, chart pack, Mermaid diagram, interactive report, export, or polished final artifact.

Do not load it before evidence or data is available unless the task is only to draft an empty template.

## Input Context
Collect the user's goal, verified evidence, data schemas, citations, selected format, language, brand template, and whether to create, edit, overwrite, or export. Infer audience, title, structure, length, layout, and visual strategy unless ambiguity changes the business outcome.

## Tool Strategy
First choose and load exactly one authoring skill:

- `presentation-authoring` for PPTX and HTML slides.
- `document-authoring` for editable Word documents.
- `pdf-publishing` for publication-ready PDF.
- `dashboard-authoring` for data-backed interactive reports.
- `diagram-authoring` for Mermaid diagrams.
- `whiteboard-authoring` for editable Excalidraw canvases.

Then use:

`search_tools -> describe_tool -> workflow.open/prepare/edit/run -> deliverable.open/edit/export`.

Before generation, use analysis/research tools to obtain evidence. Configure the selected format as a workflow
deliverable node and generate it by running the workflow. Do not call `deliverable.create` directly from the
Agent. Keep the final result separate from the execution trace.

## Steps And Checkpoints
1. Infer format and audience from the user's goal unless ambiguity changes the business outcome.
2. Build one Evidence Pack. Separate facts, calculations, inferences, recommendations, limitations, and verified sources.
3. Reuse a matching READY workflow or prepare one with the evidence inputs, analysis step, and requested deliverable node.
4. Load the format skill and create its strict intermediate specification. Do not send one generic paragraph list to every renderer.
5. Attach citations to claims, tables, charts, pages, or nodes before rendering.
6. Run the saved workflow so the format-specific renderer produces the artifact.
7. Validate the real file. Check format integrity, content, layout, provenance, and accessibility.
8. Use the quality report to repair only identified problems. Stop after two targeted repair rounds.
9. Generate the artifact only after write confirmation when required.
10. Return the workflow run and artifact id/link with a short quality summary. Stop immediately after verified completion.

## Citation And Provenance
Deliverables must include a citation map and source artifact list. A claim is not cited merely because a reference page exists; the claim or its containing block must point to the source. Preserve resource id, version, location, content hash, and verification time. Do not cite failed or unverified URLs.

## Failure Strategy
Classify failures before acting. Fix schema errors by correcting fields, evidence gaps by finding or requesting evidence, and renderer failures by changing layout or content density. Retry transient network failures only. If evidence is insufficient, produce a clearly labelled draft or gap list instead of fabricating. If export fails, preserve the editable artifact and report the failed export step.

## Human Confirmation Boundary
Creating, editing, overwriting, deleting, or exporting deliverables requires confirmation according to tool policy.

## Output Contract
Return artifact id, format, title, version, citation count, provenance summary, validation status, repaired issues, and any remaining review items.

## Example Tasks
- Generate a one-page PPT from this analysis.
- Export the report to PDF.
- Create a Mermaid risk transmission diagram with cited evidence.
