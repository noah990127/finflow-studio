---
name: excel-analysis
description: Use for spreadsheet, CSV, Excel, workbook profiling, formulas, transformations, reconciliation, joins, validation, and large table analysis.
---
# Excel Analysis

## Load When
Use this skill when the user mentions Excel, CSV, workbook, worksheet, formula, macro-like logic, reconciliation, data cleaning, joining, transforming, profiling, or spreadsheet-driven analysis.

Do not load it for document-only research with no tabular work.

## Input Context
Collect workbook/resource id, sheet names, selected range, columns, expected result, formulas/business rules, row volume, and output destination.

## Tool Strategy
Use:

`open_resource/read_resource -> profile_dataset/profile_spreadsheet -> query_dataset/query_duckdb -> transform_dataset -> create_deliverable`.

For large data, use dataset/query tools instead of loading everything into prompt context. Keep transformations transparent and reproducible.

## Steps And Checkpoints
1. Profile sheets, headers, types, row counts, missing values, and duplicates.
2. Clarify ambiguous formulas, join keys, fiscal periods, and units.
3. Draft transformations without overwriting source data.
4. Validate sample rows and totals.
5. Preserve SQL/formula/script provenance.
6. Ask confirmation before saving transformed output or editing workbook resources.

## Citation And Provenance
For outputs, record source file id, sheet/range or dataset id, query/script, generated dataset id, and validation checks.

## Failure Strategy
If a workbook is too large, switch to chunked import or dataset query. If headers are ambiguous, return a profiling summary and ask for mapping confirmation.

## Human Confirmation Boundary
Any write to dataset, workbook, resource tree, deliverable, or export requires confirmation.

## Output Contract
Return data profile, transformation plan, validation results, generated dataset/artifact if confirmed, and provenance.

## Example Tasks
- Clean this Excel file and calculate monthly revenue by region.
- Join API data to the selected workbook using company code.
- Profile the dataset and identify abnormal values.
