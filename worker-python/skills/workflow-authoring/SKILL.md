---
name: workflow-authoring
description: Use to create, edit, connect, run, version, or explain deterministic workflows made of project resources, data transforms, analysis nodes, and deliverables.
---
# Workflow Authoring

## Load When
Use this skill when the user asks to create, edit, add nodes, remove nodes, connect, run, save a version, schedule, or reuse a workflow. Also load it for research, analysis, data processing, and deliverable tasks because FinFlow performs those tasks through visible, reusable workflows.

Do not load it only when the user wants a conversational answer that does not analyze project materials, transform data, or create an artifact.

## Input Context
Identify project id, existing workflow id/name, goal, inputs, desired nodes, outputs, execution mode, schedule, version expectations, and selected resources.

## Tool Strategy
Use:

`search_tools -> describe_tool -> workflow.open/prepare/edit/add_node/remove_node/connect/run/save_version`.

Workflow is a tool the Agent can call, not the Agent runtime. Use workflows to make a proven process repeatable.

Before creating anything, inspect the project's READY workflows. Reuse one only when its actual inputs,
processing objective, and output formats cover the current goal. Otherwise call `workflow.prepare` with
the complete goal, requested output formats, and a model-authored `workflow_spec`, then run the returned workflow ID. Never bypass this
sequence with `deliverable.create`; output artifacts are created by deliverable nodes during `workflow.run`.

`workflow_spec` is the dynamic business plan, not a fixed template. Use version `1.0` and provide a short
name, description, and 3-8 single-purpose nodes (maximum 12). Every node must include a unique `key`,
supported `type`, business-readable `name`, `purpose`, complete `config`, and `depends_on`. Use `$inputs`
only when the node should consume the current project's available inputs. Represent independent research
or analysis as parallel nodes and converge them explicitly before a deliverable. Do not add a node merely
to make the canvas look more complete.

For `REF_SEARCH`, write a focused query rather than copying the full user message. For `AI_ANALYSIS`,
include dimensions, evidence rules, uncertainty handling, and output structure in `config.prompt`.
For `DATA_TRANSFORM`, describe the business transformation and output name; use it only with structured
inputs. For `DELIVERABLE`, specify generation requirements, format, citation settings, and the selected
PPT skill when applicable. The Java gateway validates the graph, compiles dependencies, lays out the
canvas, and rejects invalid or cyclic plans.

For a known graph, use one structured `workflow.edit` patch to upsert nodes and connect them together.
Supply stable IDs for new nodes; reuse returned IDs for existing nodes. `nodes` merges by ID,
while `edges`, when supplied, replaces the complete edge list. Preserve existing connections deliberately.
Do not send prose or JSON Patch arrays as `patch`. Each analysis node needs its complete
`config.prompt`; Agent nodes need `config.instruction`; deliverables need `config.generationPrompt`.
Preserve user-provided rules, scoring weights, exact output schemas, and citation requirements in full.
Use the workflow and version returned by a mutation to check the result without reopening it repeatedly.
If `changed` is false, do not repeat the same edit. Correct invalid parameters or explain the blocker.

## Steps And Checkpoints
1. Inspect the project and list candidate READY workflows.
2. Open likely candidates and compare actual inputs, objective, and outputs with the current goal.
3. Reuse a matching workflow; otherwise prepare a new complete workflow from real project inputs.
4. Keep each node single-purpose and visibly connected.
5. Add a content-review node only when it improves the stated business result; do not model organizational approval inside Studio.
6. Save a version after meaningful changes.
7. Run only when inputs and parameters are sufficiently clear, then verify node results and artifacts.

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
The Java gateway enforces permissions and the session's AUTO or approval mode. Honor its confirmation
response; do not invent a second confirmation requirement in AUTO mode or bypass a required approval.

## Output Contract
Return workflow id, whether it was reused or created, changed nodes, connection summary, version/run id, generated artifacts, pending confirmations, and provenance impact.

## Example Tasks
- Build a monthly operating analysis workflow.
- Add the selected Excel file as the input node.
- Run the existing report workflow and summarize generated artifacts.
