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
1. Confirm format and audience.
2. Build an outline from verified evidence.
3. Attach citations to claims, tables, and charts.
4. Generate draft artifact only after write confirmation when required.
5. Verify artifact metadata and provenance.
6. Return final summary plus artifact id/link.

## Citation And Provenance
Deliverables must include a citation map and source artifact list. Do not drop citations when transforming insights into slides or documents.

## Failure Strategy
If source material is insufficient, produce an outline and evidence gaps instead of fabricating. If export fails, preserve the editable artifact and report the failed export step.

## Human Confirmation Boundary
Creating, editing, overwriting, deleting, or exporting deliverables requires confirmation according to tool policy.

## Output Contract
Return artifact id, format, title, sections/slides, citation count, provenance summary, and any remaining review items.

## Example Tasks
- Generate a one-page PPT from this analysis.
- Export the report to PDF.
- Create a Mermaid risk transmission diagram with cited evidence.
