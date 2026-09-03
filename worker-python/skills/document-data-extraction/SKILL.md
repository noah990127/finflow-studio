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

`open_resource/read_resource -> parse_resource/read_document -> extract_table -> create_dataset/import_dataset -> search_knowledge`.

Prefer structured parsers and table extractors over string slicing. Use page or section hints when the user provides them.

## Steps And Checkpoints
1. Inspect resource metadata and file type.
2. Parse text and headings before extracting targeted fields.
3. Extract tables with original headers and units.
4. Validate row counts, column names, merged headers, empty values, and confidence.
5. Attach citation locators to each extracted row/cell when possible.
6. Ask confirmation before creating or overwriting a dataset.

## Citation And Provenance
Record resource id, page, section, table title, bounding box when available, extraction tool, timestamp, and confidence. Preserve raw snippets for audit.

## Failure Strategy
If extraction confidence is low, return a preview and ask the user to confirm pages or table hints. If a file cannot be parsed, suggest conversion or manual upload of a better source.

## Human Confirmation Boundary
Creating datasets, editing resources, moving files, deleting files, or exporting extracted data requires confirmation.

## Output Contract
Return extracted text/table summary, schema, row/column counts, confidence notes, citations, provenance, and proposed next action.

## Example Tasks
- Extract the revenue table from this annual report.
- Parse this PPT into sections and evidence snippets.
- Turn the selected PDF tables into a dataset with citations.
