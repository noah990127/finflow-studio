# Built-in PPT skills

`guizang-huawei-style-c` implements an editable PowerPoint renderer based on the public Style C design description from:

https://github.com/SeanDongX/guizang-ppt-skill

The upstream project is licensed under GNU AGPL-3.0. This directory does not copy its HTML template, JavaScript, SVG logo, validator, or layout source. It implements the documented red/grey corporate visual principles independently for FinFlow's native `.pptx` output.

## Frontend Slides web presentation skill

The `frontend-slides` option produces a self-contained HTML + JavaScript presentation, not a PowerPoint file. Its browser-first interaction and design principles are inspired by:

https://github.com/zarazhangrui/frontend-slides

The upstream project is licensed under MIT (Copyright (c) 2025 Zara Zhang). FinFlow's generator is implemented independently and keeps attribution in generated HTML comments and metadata.
