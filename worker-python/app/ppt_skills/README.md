# Built-in PPT skills

## Current generation path

Content planning and rendering are separate: `semantic_presentation.py` and
`presentation_templates.py` handle structured intent and templates; presentation
services coordinate output. Narrative and evidence requirements must follow the
user's purpose. Research needs traceable sources; ordinary operational reports
must not invent evidence or metrics for every slide.

See the [Blue Professional adapter](../templates/blue-professional-v2/README.md)
for upstream assets, offline dependencies and export limits. Preserve licenses and
visually inspect exported artifacts; an aggregate quality score is not proof of a
usable presentation. The options below describe individual renderers, not a promise
that PowerPoint and ONLYOFFICE render every output identically.

`guizang-huawei-style-c` implements an editable PowerPoint renderer based on the public Style C design description from:

https://github.com/SeanDongX/guizang-ppt-skill

The upstream project is licensed under GNU AGPL-3.0. This directory does not copy its HTML template, JavaScript, SVG logo, validator, or layout source. It implements the documented red/grey corporate visual principles independently for FinBTP Studio's native `.pptx` output.

## Frontend Slides web presentation skill

The `frontend-slides` option produces a self-contained HTML + JavaScript presentation, not a PowerPoint file. Its browser-first interaction and design principles are inspired by:

https://github.com/zarazhangrui/frontend-slides

The upstream project is licensed under MIT (Copyright (c) 2025 Zara Zhang). FinBTP Studio's generator is implemented independently and keeps attribution in generated HTML comments and metadata.
