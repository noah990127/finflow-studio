---
name: annual-report-analysis
description: Use for annual report, 10-K, prospectus, management discussion, segment, risk, and financial statement analysis with page-level citations.
---
# Annual Report Analysis

## Load When
Use this skill when the task mentions annual reports, filings, 10-K, prospectus, MD&A, management discussion, segment reporting, risk factors, financial statements, or multi-year report comparison.

Do not load it for pure spreadsheet modeling with no filing evidence.

## Input Context
Collect company name, fiscal years, report resources, language, target sections, requested metrics, and final output. If multiple filings exist for one year, prefer official reports and note amended versions.

## Tool Strategy
Use:

`search_resources -> read_document -> extract_table -> search_knowledge -> query_dataset/query_duckdb -> create_deliverable`.

Read table of contents or section headings first when available. Extract tables for financial statements. Search text for management explanations, segment changes, risks, strategy, accounting policy, and non-recurring events.

## Steps And Checkpoints
1. Find all relevant reports and verify fiscal year coverage.
2. Build a section map: financial statements, MD&A, business review, segments, risk, notes.
3. Extract core financial tables with page numbers and table names.
4. Compare periods using consistent units and accounting scope.
5. Link each major metric movement to report language.
6. Note restatements, discontinued operations, segment reclassification, currency changes, and auditor emphasis.
7. Summarize only supported claims.

## Citation And Provenance
Use page, section, resource title, table name, and short quote/table locator. Never cite a report broadly when the claim came from a specific page or table.

## Failure Strategy
If OCR or parsing is weak, request a clearer file or ask to run a document extraction workflow. If a year is missing, analyze the available years and mark the gap.

## Human Confirmation Boundary
Confirmation is needed for writing extracted datasets, saving a workflow, generating or exporting deliverables, or replacing existing parsed data.

## Output Contract
Return report coverage, extracted metrics, narrative drivers, risks, citation list, provenance chain, and recommended follow-up checks.

## Example Tasks
- Read these five annual reports and explain revenue growth drivers.
- Extract the income statement table and cite each value.
- Compare risk factor changes between two fiscal years.
