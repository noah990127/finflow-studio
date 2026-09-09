---
name: pdf-publishing
description: Publish stable, searchable, accessible PDF reports or fixed-layout materials with pagination, bookmarks, metadata, font checks, citations, and page-level visual verification.
---
# PDF Publishing

## Load When
Load when the required final result is PDF. For reports, plan content as `DocumentSpec` first. Use direct fixed-layout planning only for one-pagers, posters, forms, or certificates.

## Input Context
Require user goal, Evidence Pack, publication type, citations, language, and print/screen context. Infer page size unless the user specifies it.

## Tool Strategy
Create `PublicationSpec`. Render text as real text, not full-page images. Preserve semantic order, headings, lists, tables, links, metadata, and bookmarks.

## Steps And Checkpoints
1. Choose report-flow or fixed-layout publication.
2. Plan page size, hierarchy, reading order, table behavior, figures, bookmarks, and references.
3. Embed or safely substitute fonts and preserve Unicode.
4. Render the PDF.
5. Reopen it, extract text, and render every page to images.
6. Check blank pages, clipping, missing glyphs, broken links, incorrect reading order, table splits, and print margins.

## Failure Strategy
Repair the identified page or block only. If tagged PDF support is unavailable, report the accessibility limitation instead of claiming compliance.

## Human Confirmation Boundary
Follow the active Auto or Approval mode. Publishing or exporting the final PDF uses the configured write/export confirmation policy; preflight does not require confirmation.

## Output Contract
Return the PDF, page count, searchable-text status, bookmark/accessibility status, citation coverage, visual preflight result, and quality score.

## Example Tasks
- Publish this research report as a searchable PDF.
- Create a one-page executive brief for printing.
