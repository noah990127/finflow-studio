---
name: document-data-extraction
description: Use to parse PDF, Word, PowerPoint, image, web, or mixed office files into text, tables, structured fields, datasets, and citeable evidence.
---
# Document Data Extraction

## Load When
Use this skill when the task asks to read, parse, extract, OCR, turn a document into data, extract tables, capture figures, or build a dataset from unstructured files.

Do not load it for already clean tabular datasets unless document provenance is still needed.

## Input Context
Identify file ids, file types, target pages or sections, desired fields/tables, expected schema, quality requirements, and whether a new dataset should be created.

## Tool Strategy
Use:

`resource.open/resource.read -> knowledge.parse/knowledge.read -> knowledge.extract_table or dataset.extract -> dataset.query/dataset.transform`.

Prefer structured parsers and table extractors over string slicing. Use page or section hints when the user provides them.
For a web JSON or HTML source, call `resource.read` first, then `dataset.extract`. Treat the returned `datasetId` as the only valid input id for later dataset tools; the original web `resource_id` is provenance, not a dataset.
When transforming the extracted dataset, either provide one read-only DuckDB `SELECT`/`WITH` query over the table `source`, or omit `script` and state the transformation precisely in `requirements`.

## Steps And Checkpoints
1. Inspect resource metadata and file type.
2. Parse text and headings before extracting targeted fields.
3. Extract tables with original headers and units.
4. Verify that extraction returned a new dataset id and that it resolves in the workspace.
5. Validate row counts, column names, merged headers, empty values, and confidence.
6. Attach citation locators to each extracted row/cell when possible.
7. Ask confirmation before creating or overwriting a dataset when approval mode is active; continue automatically in Auto mode.

## Citation And Provenance
Record resource id, page, section, table title, bounding box when available, extraction tool, timestamp, and confidence. Preserve raw snippets for audit.

## Failure Strategy
If extraction confidence is low, return a preview and ask the user to confirm pages or table hints. If a file cannot be parsed, suggest conversion or manual upload of a better source.

## Human Confirmation Boundary
Creating datasets, editing resources, moving files, deleting files, or exporting extracted data requires confirmation in approval mode. Auto mode proceeds under the user's standing authorization while preserving the audit trail.

## Output Contract
Return extracted text/table summary, schema, row/column counts, confidence notes, citations, provenance, and proposed next action.

## Example Tasks
- Extract the revenue table from this annual report.
- Parse this PPT into sections and evidence snippets.
- Turn the selected PDF tables into a dataset with citations.
