---
name: workspace-operations
description: Use for project, folder, resource, dataset, knowledge, workflow, deliverable, and UI navigation operations in the left workspace.
---
# Workspace Operations

## Load When
Use this skill when the task manipulates the left workspace: projects, folders, files/resources, datasets, data sources, knowledge, workflows, deliverables, or UI panels.

Do not load it for pure analytical reasoning that does not need workspace actions.

## Input Context
Collect current project, selected resource/folder/panel, requested operation, target name/path, destination, overwrite/delete intent, and expected UI navigation.

## Tool Strategy
Always discover capabilities:

`search_tools -> describe_tool -> tool_call`.

Treat the workspace as the Agent environment. Read/navigation tools can run with low risk; write, destructive, and export tools follow confirmation policy.

## Operation Coverage
Project: list, create, rename, delete, open.

Folder: create, rename, move, delete.

Resource/file: upload/add, open, read, edit, rename, move, delete.

Data source/dataset: add, connect, import, query, extract, create, transform, open, delete.

Knowledge: add, search, read, parse, extract table.

Workflow: create, open, edit, add node, remove node, connect, run, save version.

Deliverable: create, open, edit, export, delete.

UI: navigate, select, open panel.

## Risk Labels
`read` means no persistent change. `write` creates or edits durable workspace state. `destructive` deletes or overwrites. `export` writes outside the editable workspace or produces user-facing files.

## Citation And Provenance
Workspace operations should carry trace id, actor, project id, target ids, input summary, output ids, and citations when content is read or transformed.

## Failure Strategy
If a target cannot be uniquely resolved, search/list first. If delete/overwrite is requested, require explicit confirmation. If permissions block the action, report the blocked tool and scope.

## Human Confirmation Boundary
All write, destructive, export, external connect, and overwrite actions require Java Tool Gateway confirmation unless an approved policy says otherwise.

## Output Contract
Return operation result, target ids, UI action, confirmation state, audit/provenance summary, and next available actions.

## Example Tasks
- Rename this project and open its workflow panel.
- Move the selected file into the knowledge folder.
- Create a dataset from this spreadsheet and open it.
