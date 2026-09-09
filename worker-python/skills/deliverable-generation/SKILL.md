---
name: deliverable-generation
description: Use to create, edit, export, or structure PPT, Word, PDF, Mermaid, Excalidraw, HTML slides, and interactive reports from verified content.
---
# Deliverable Generation

## Load When
Use this skill when the user asks for a report, slide, presentation, document, chart pack, Mermaid diagram, interactive report, export, or polished final artifact.

Do not load it before evidence or data is available unless the task is only to draft an empty template.

## Input Context
Collect audience, format, length, tone, source content, charts, required citations, language, branding/style, and whether to create new, edit existing, overwrite, or export.

## Tool Strategy
Use:

`search_tools -> describe_tool -> create_deliverable/open_deliverable/edit_deliverable/export_deliverable`.

Before generation, use analysis/research tools to obtain evidence. Keep final result separate from execution trace.

## Steps And Checkpoints
1. Infer format and audience from the user's goal unless ambiguity changes the business outcome.
2. Build a narrative outline from verified evidence before rendering any slide.
3. Make every slide express one core conclusion; align chapter hierarchy with slide order.
4. Keep at most three supporting points on a slide and choose statement, metric, chart, comparison, timeline, process, matrix, or list layout from the content type.
5. Prefer native charts and visual structures over long paragraphs when the evidence contains suitable data.
6. Attach citations to claims, tables, and charts.
7. Run a presentation-quality review for logic, density, layout fit, and provenance, then revise once when needed.
8. Generate the artifact only after write confirmation when required.
9. Verify artifact metadata and provenance.
10. Return final summary plus artifact id/link.

## Citation And Provenance
Deliverables must include a citation map and source artifact list. Do not drop citations when transforming insights into slides or documents.

## Failure Strategy
If source material is insufficient, produce an outline and evidence gaps instead of fabricating. If export fails, preserve the editable artifact and report the failed export step.

## Human Confirmation Boundary
Creating, editing, overwriting, deleting, or exporting deliverables requires confirmation according to tool policy.

## Output Contract
Return artifact id, format, title, sections/slides, citation count, provenance summary, presentation-quality status, and any remaining review items.

## Example Tasks
- Generate a one-page PPT from this analysis.
- Export the report to PDF.
- Create a Mermaid risk transmission diagram with cited evidence.
