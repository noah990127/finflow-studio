---
name: presentation-authoring
description: Design editable enterprise presentations around audience intent, semantic relationships, licensed templates and inspection of the actual output.
---
# Presentation Authoring

## Design Before Rendering
Infer audience, use (speaking or reading), and what the reader should understand or decide.
Do not impose a research argument on a plan, training deck, status update, or proposal.
No universal three-point limit, conclusion-title requirement, chart quota or action ending.
Use short topic titles for explanations and supported takeaway titles for findings.
Merge sparse material instead of turning each source paragraph or week into a slide.

## New PPT Work
Use `blue-professional-v2`. This reuses Blue Professional HTML layout CSS and the
MIT html-to-pptx converter. It does not claim to use the Presenton export engine.
Store the audience/intent and the semantic schema in the fixed PROMPT, not just the chat.
Each section has `heading`, `coreMessage`, `visual`, `layout` (equal to visual.kind),
empty `paragraphs`/`bullets`, real `refIds`/`citations` where needed. Do not send `chart`.

`visual` includes a short non-visible `rationale` and one of:
- `timeline` or `process`: `items` with `label`, `detail`, `owner`, `outcome`, 2-5 ordered stages.
- `matrix` or `comparison`: `columns` and `rows:[{label,cells}]`, 1-5 columns, 1-6 rows.
  Cells correspond exactly to columns. Compare alternatives along shared criteria.
- `statement` or `list`: `items:[{label,detail}]`, 1-6 independent items only when no stronger relationship exists.

Unknown owners/outcomes stay empty or explicitly pending; never fabricate facts to fill a template.
An onboarding plan can use a four-phase roadmap and a role-by-phase matrix; do not hardcode this example for other topics.
Templates cannot add unsupported meaning. Detailed numeric chart work may use an explicitly selected existing native-chart template.

## Execution And Review
Save structured content as an inspectable project resource, then call `document.render`.
Keep old implementations and outputs intact unless the user requests a change.
Replay uses current inputs with the same saved prompt, template version and tool steps.
Check the actual exported pages, not only the content JSON: coverage, legibility, relationships,
hierarchy, redundancy and whether the chosen structure helps the audience.
Technical validation is not aesthetic approval. Never report a quality score or claim visual
review without inspecting the output. When rendering reports a defect, repair the specific
design and re-execute before claiming completion; do not silently downgrade to a text list.

## Human Confirmation Boundary
Follow the active Auto or Approval mode when creating, replacing, exporting or sharing a deck.
Planning, inspecting the output and checking its contents are read-only.

## Sources And Handoff
Research/external claims need real source records. Internal plans do not need fake evidence.
Keep provenance in notes; do not force a visible evidence box on every page.
Return the project artifact location and any actual limitations, not self-awarded ratings.
An artifact saved and opened in the platform is delivered. Do not append repeated export
or sharing steps unless the user explicitly requests a separate export or share.
