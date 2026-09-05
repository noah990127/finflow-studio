---
name: workflow-authoring
description: Use to create, edit, connect, run, version, or explain deterministic workflows made of project resources, data transforms, analysis nodes, and deliverables.
---
# Workflow Authoring

## Load When
Use this skill when the user asks to create, edit, add nodes, remove nodes, connect, run, save a version, schedule, or reuse a workflow.

Do not load it when the user only wants a one-off answer and has not asked to save or automate the process.

## Input Context
Identify project id, existing workflow id/name, goal, inputs, desired nodes, outputs, execution mode, schedule, version expectations, and selected resources.

## Tool Strategy
Use:

`search_tools -> describe_tool -> workflow.create/open/edit/add_node/remove_node/connect/run/save_version`.

Workflow is a tool the Agent can call, not the Agent runtime. Use workflows to make a proven process repeatable.

## Steps And Checkpoints
1. Inspect the project and current workflow.
2. Map real inputs before creating processing nodes.
3. Keep each node single-purpose and visibly connected.
4. Insert confirmation or review points before risky writes.
5. Save a version after meaningful changes.
6. Run only when inputs and parameters are sufficiently clear.

## Citation And Provenance
Workflow run outputs must preserve upstream resource, dataset, tool, node, run id, and artifact provenance.

## Node Variables
`workflow.open` returns the workflow definition and `nodeOutputs` with each node's output fields and types.
Use stable node IDs from that response, never display names or invented IDs. Connect dependencies before referencing them.
Text requirements (`prompt`, `generationPrompt`, `instruction`, `requirements`, `query`) support
`{{#node_id.output#}}` and nested object/array paths such as `{{#node_id.sources.0.sourceName#}}`.
For analysis, Agent, and deliverable nodes, bind their input content explicitly with
`config.inputSource = {"nodeId": "analysis_id", "path": ["output"]}`.
Omitting inputSource preserves automatic collection from directly connected upstream nodes.
Use field selectors only from reachable ancestors. Missing variables fail validation or execution;
never replace a missing value with invented content. Do not interpolate variables into SQL or executable scripts.
Preserve `sources` and `refIds` across analysis and generation; switching off visible citations does not remove provenance.

## Failure Strategy
If required inputs are missing, create a draft workflow with placeholders only when the user asked for a draft. If run fails, report failed node, logs, and recoverable next steps.

## Human Confirmation Boundary
Creating, editing, running write-capable workflows, deleting nodes, saving versions, and exporting workflow results require confirmation.

## Output Contract
Return workflow id, changed nodes, connection summary, version/run id, pending confirmations, and provenance impact.

## Example Tasks
- Build a monthly operating analysis workflow.
- Add the selected Excel file as the input node.
- Run the existing report workflow and summarize generated artifacts.
