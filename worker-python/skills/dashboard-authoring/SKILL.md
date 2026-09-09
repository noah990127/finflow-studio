---
name: dashboard-authoring
description: Create data-backed interactive financial or operating reports with metric definitions, field mappings, chart rationale, filters, linked views, provenance, accessibility, and browser validation.
---
# Dashboard Authoring

## Load When
Load only when the user explicitly asks for a dashboard or interactive report and the project has queryable data. Do not create an empty dashboard that merely asks for CSV later.

## Input Context
Require dataset schema/profile, sample rows, data freshness, user questions, Evidence Pack, and citations to snapshots or queries.

## Tool Strategy
Create `DashboardSpec`. Define metrics before charts. Explicitly map dimensions and measures to visual channels. Use browser rendering and interaction tests.

## Steps And Checkpoints
1. Profile types, missing values, date grain, categories, measures, currencies, and units.
2. Define each KPI formula, aggregation, denominator, period, comparison basis, and source.
3. Build an analysis story, then select only views that answer its questions.
4. Choose charts by relationship: trend, comparison, composition, distribution, correlation, or detail.
5. Define filters, linkage, drill-down, number formatting, and explanatory text.
6. Implement loading, empty, partial-data, and error states.
7. Validate fields, formulas, mappings, responsiveness, ARIA descriptions, interactions, performance, and export.

## Failure Strategy
Reject missing fields and ambiguous formulas with exact correction hints. Never invent data or silently change units. If no usable data exists, create a normal report or request data instead.

## Human Confirmation Boundary
Follow the active Auto or Approval mode. Data profiling and preview are read-only; creating, replacing, or exporting a report follows the corresponding tool policy.

## Output Contract
Return the report, dataset/query versions, KPI catalog, views, filters, validation status, citation coverage, and quality score.

## Example Tasks
- Build a filterable revenue and margin dashboard from this dataset.
- Create an operating cockpit with monthly trend and regional drill-down.
