---
name: presentation-authoring
description: Create professional PPTX or browser slide presentations from verified evidence, with one core claim per page, page-level storytelling, visual layout selection, citations, and render verification.
---
# Presentation Authoring

## Load When
Load for PPTX, slides, decks, management presentations, pitches, reviews, or browser presentations. Do not load for long-form reading documents or exploratory dashboards.

## Input Context
Require the user goal, Evidence Pack, selected PPTX or HTML format, citations, language, and optional brand template. Infer audience and length from the task.

## Tool Strategy
Create `PresentationSpec`, render through a template engine, then reopen the deck or browser page. Use native charts when structured data exists. Never paste CSS, Markdown, or layout instructions into visible slide content.

## Steps And Checkpoints
1. State the decision or takeaway the presentation must leave with the audience.
2. Build a story arc: context, findings, implications, action, risks, close.
3. Map each outline level to chapters and pages.
4. Give every page one conclusion-style title and one core message.
5. Select statement, metric, chart, comparison, timeline, process, matrix, or list layout from content semantics.
6. Keep at most three supporting points per page; move detail to notes or appendix.
7. Bind source references to claims and charts.
8. Render and inspect every page for overflow, overlap, tiny text, empty regions, and broken charts.
9. Review the whole narrative for repetition, missing transitions, and unsupported conclusions.

## Quality Rules
Titles are conclusions, not topic labels. One page has one core point. Visuals must explain evidence. Charts include measure, unit, period, and source. A presentation must have a clear opening, body, and close.

## Failure Strategy
Split dense pages, replace invalid charts with a supported visual, and remove repetitive pages. Do not shrink text below the readable threshold to make content fit.

## Human Confirmation Boundary
Follow the active Auto or Approval mode. Overwriting or exporting an existing presentation remains subject to the tool policy; quality repair within the already approved creation is not a new confirmation.

## Output Contract
Return the deck, page count, citation count, render status, quality score, and repaired page issues.

## Example Tasks
- Create a board-ready operating review from three annual reports.
- Turn this analysis into an HTML presentation for browser sharing.
