---
name: document-authoring
description: Create editable, structured Word documents such as reports, proposals, memos, SOPs, and minutes with coherent argument, styles, tables, figures, citations, and pagination checks.
---
# Document Authoring

## Load When
Load for DOCX or an editable formal document. Do not load when the primary use is live presentation, visual whiteboarding, or interactive data exploration.

## Input Context
Require the user goal, Evidence Pack, citations, language, and optional organization template. Infer report, proposal, memo, SOP, or minutes from the task.

## Tool Strategy
Create `DocumentSpec` before rendering. Use structural Word styles for titles, headings, body, lists, tables, captions, and references. Use complete paragraphs for reasoning; use bullets only for parallel items.

## Steps And Checkpoints
1. Select the document type and its professional structure.
2. Draft an executive summary that accurately represents the full document.
3. Give each section a purpose, core claim, evidence, and transition.
4. Use tables for structured comparison and figures for data relationships.
5. Add captions, cross-references, citations, contents, metadata, headers, and page numbers where appropriate.
6. Render and check heading order, orphan headings, paragraph length, table width, figure placement, page breaks, and missing glyphs.
7. Reopen the DOCX to ensure it does not request repair and remains editable.

## Citation And Provenance
Bind citations to the paragraphs, tables, and figures they support. A bibliography alone is insufficient.

## Failure Strategy
If a section lacks evidence, lower the certainty or mark the gap. If pagination fails, adjust block boundaries or table layout rather than deleting content.

## Human Confirmation Boundary
Follow the active Auto or Approval mode. Confirm creation, overwrite, or export when tool policy requires it; internal planning and validation are read-only.

## Output Contract
Return the DOCX, document type, section count, citation coverage, reopen status, pagination findings, and quality score.

## Example Tasks
- Write a formal research report from interviews and survey data.
- Turn the current process into an editable SOP.
