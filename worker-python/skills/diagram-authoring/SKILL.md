---
name: diagram-authoring
description: Create precise Mermaid flow, sequence, state, ER, timeline, or Gantt diagrams from structured relationships, with controlled complexity, accessible descriptions, syntax parsing, and render validation.
---
# Diagram Authoring

## Load When
Load when relationships, ownership, sequence, state, data model, or timing are better expressed as a diagram. Do not load merely to decorate a text report.

## Input Context
Require the question, verified entities and relationships, desired emphasis, and relevant citations.

## Tool Strategy
Create `DiagramSpec` first, then compile to Mermaid. Select the diagram type from semantics. Parse and render the final source before completion.

## Steps And Checkpoints
1. Choose flowchart, sequence, state, ER, timeline, or Gantt.
2. Normalize short node names and explicit edge meanings.
3. Add groups or swimlanes when ownership matters.
4. Label decision branches and preserve the real direction of causality or time.
5. Keep the overview under 24 core nodes; split complex material into subdiagrams.
6. Add `accTitle`, `accDescr`, and a text relationship summary.
7. Parse Mermaid syntax and render it to confirm the result is nonblank and readable.

## Failure Strategy
Fix syntax from parser feedback. If the graph is too dense, split it; do not simply reduce font size or remove relationships without explanation.

## Human Confirmation Boundary
Follow the active Auto or Approval mode. Parsing and preview are read-only; saving, replacing, or exporting the diagram follows tool policy.

## Output Contract
Return Mermaid source, diagram type, node/edge counts, parser status, render status, accessibility status, and quality score.

## Example Tasks
- Draw the approval flow with all rejection paths.
- Show the service call sequence for a model tool invocation.
