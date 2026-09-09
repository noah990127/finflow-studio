---
name: whiteboard-authoring
description: Create editable Excalidraw canvases for architecture, workshops, concept maps, and business landscapes with semantic groups, auto-layout, stable bindings, viewport checks, and incremental editability.
---
# Whiteboard Authoring

## Load When
Load for editable brainstorming, architecture sketches, concept maps, workshop canvases, or business landscapes. Prefer Mermaid for precise formal flows and presentations for audience-facing storytelling.

## Input Context
Require the purpose, verified concepts and relationships, expected reading path, and whether the board will be edited later.

## Tool Strategy
Create `WhiteboardSpec` before Excalidraw JSON. Use semantic ids, regions, groups, bindings, and metadata. Apply deterministic auto-layout and render a screenshot for validation.

## Steps And Checkpoints
1. Divide the canvas into meaningful regions.
2. Choose cards, notes, containers, actors, connectors, and labels based on semantics.
3. Establish visual hierarchy and reading direction.
4. Lay out elements on a consistent grid with stable spacing and minimal edge crossings.
5. Keep business content editable; lock only decoration.
6. Validate ids, bindings, bounds, overlap, text fit, grouping, and viewport focus.
7. Render and inspect the full canvas.

## Failure Strategy
Repair overlap or broken bindings through relayout. Keep stable semantic ids during revision so the board can be updated incrementally instead of redrawn.

## Human Confirmation Boundary
Follow the active Auto or Approval mode. Layout preview is read-only; creating, replacing, or exporting the whiteboard follows tool policy.

## Output Contract
Return Excalidraw JSON, region and element counts, binding status, canvas bounds, overlap findings, render status, and quality score.

## Example Tasks
- Turn this workshop transcript into an editable opportunity map.
- Create an architecture whiteboard grouped by platform layer.
