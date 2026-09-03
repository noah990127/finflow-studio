---
name: financial-analysis
description: Use for company, industry, KPI, profitability, growth, cash flow, margin, scenario, variance, and management reporting analysis that requires numeric evidence and provenance.
---
# Financial Analysis

## Load When
Use this skill when the user asks to analyze operating performance, financial trends, KPIs, variances, profitability, cash flow, business drivers, peer comparison, scenario impact, or management conclusions.

Do not load it for simple navigation, file organization, generic writing, or document summarization without financial metrics.

## Input Context
Identify the company or subject, time range, metric scope, currency/unit, available datasets, relevant documents, selected resources, and requested output format. If the user asks for a conclusion but no evidence exists, first search project resources before answering.

## Tool Strategy
Start with `search_tools` and `describe_tool` when the needed capability is not already in context. Prefer read tools before write tools:

`workspace.inspect -> search_resources -> read_resource/read_document -> extract_table/query_dataset -> search_knowledge -> create_deliverable/export_deliverable`

Use `query_dataset` or `query_duckdb` for calculations instead of mental arithmetic when tabular data is available. Use `search_knowledge` to explain drivers after numeric changes are known.

## Steps And Checkpoints
1. Confirm the analysis question, entity, period, and output contract.
2. Locate available datasets and source documents. Record resource ids.
3. Extract or query metrics. Keep original labels, units, fiscal periods, and source citations.
4. Normalize data only when the basis is clear. Flag restatements, changed segments, missing periods, and mixed units.
5. Compute trend, YoY, CAGR, contribution, margin, or variance as needed.
6. Search evidence for major movements and management explanations.
7. Separate facts, calculations, interpretation, and uncertainty.
8. Before creating, overwriting, exporting, or publishing artifacts, stop for confirmation if the tool requires it.

## Citation And Provenance
Every numeric claim must carry citation/provenance from the source table, dataset, document page, or query trace. Preserve the chain:

`resource -> extracted table/dataset -> query result -> insight -> deliverable`.

If a metric is calculated, cite the source values and include the formula or SQL in provenance.

## Failure Strategy
If data is missing, say what is missing and suggest the exact resource or dataset needed. If evidence conflicts, present both versions and do not force a conclusion. If a tool fails, retry only when parameters can be improved; otherwise return a partial answer with the failed step.

## Human Confirmation Boundary
Require confirmation for creating projects, writing datasets, editing files, modifying workflows, creating deliverables, exporting files, deleting anything, or connecting external data sources.

## Output Contract
Return an executive summary, key metrics table, driver analysis, risks/uncertainties, cited evidence list, and next recommended action. When generating a deliverable, include artifact id/link and provenance summary.

## Example Tasks
- Analyze Huawei revenue and margin trends over the last five years.
- Explain why 2025 operating profit changed and generate a one-page management slide.
- Compare two companies using uploaded financial statements and cite the source tables.
