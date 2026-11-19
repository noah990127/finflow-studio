# FinBTP Studio curated demo cases

## Compatibility note

These bundles include historical technical workflows and offline artifacts. They
are import/regression fixtures, not the current user-facing workflow editor. The
current product uses Agent-managed business stages under My Work and reveals
technical execution on demand. Import validation does not prove external model,
search or legacy workflow execution in the destination environment.

This directory contains four portable showcase cases with ready-to-view snapshots:

- `nvidia`: NVIDIA FY2022-FY2026 operating and financial-quality analysis;
- `global-tax-2026`: 2026 global tax-policy trends, simulated entity exposure and action planning.
- `data-preparation`: governed multi-source data preparation with CSV facts, an Excel dimension,
  a streaming API, a database table, a strict transform plan and reproducible wide-table benchmark.
- `budget-variance-mvp`: monthly budget forecast versus actual analysis with online actual snapshots,
  reviewed manual adjustments, region and product hierarchies, external-assistant evidence and an HTML report.

No API key, database password, private document or local absolute path is included. All quarterly splits, entity exposure and cash-impact scenarios marked as simulated are demonstration data.

## Included capabilities

The showcase projects combine PostgreSQL, streaming data-service APIs, CSV, Markdown, PDF, Word and official web references. Their workflows include transparent DuckDB SQL, Ref retrieval, AI analysis, citation-enabled interactive reports, PowerPoint, HTML slides, editable Word output, Mermaid or Excalidraw diagrams. They intentionally omit the manual-review node.

Each case also includes selected processed datasets and final deliverables under `assets/` and `outputs/`. The importer restores those artifacts without calling an LLM, rewrites source IDs for the destination project, and preserves citation metadata. Users can inspect the complete case immediately and still run the workflow to create later versions.

## Start with Docker Compose

```bash
docker compose -f docker-compose.yml -f examples/cases/docker-compose.cases.yml up -d
```

Initialize every bundled demonstration table:

```bash
for file in examples/cases/*/postgres-init.sql; do
  docker compose exec -T postgres psql -U finflow -d finflow < "$file"
done
```

Import all cases through the public FinBTP Studio API:

```bash
python3 examples/cases/import_cases.py --api http://127.0.0.1:8080
```

The importer signs in through the public login API. It uses the local `default` demo account by default;
override it with `--username` and `--password`, or `FINFLOW_IMPORT_USERNAME` and
`FINFLOW_IMPORT_PASSWORD`.

Import one or more cases by repeating `--case`:

```bash
python3 examples/cases/import_cases.py --case nvidia --case global-tax-2026
```

The importer is idempotent for projects, source files, connections and bundled artifacts. Re-running it updates each project's workflow and imports a new artifact version only when the bundled file checksum changed.

For direct local development, `scripts/start-local.sh` also starts the demo data API on port `8011`. Load `.env` before importing so the four API connections use `127.0.0.1`; Docker Compose keeps the default `demo-api:8011` service address.

## Offline deployment

The offline image bundle must contain `finflow-demo-api:1.0.0` in addition to the main FinBTP Studio images. On the restricted server, load the images and start with `--no-build`:

```bash
docker compose -f docker-compose.yml -f examples/cases/docker-compose.cases.yml up -d --no-build
```

The importer uses only the Python standard library and requires no package installation.

## Optional endpoint overrides

```bash
export FINFLOW_NVIDIA_API_URL=http://demo-api:8011/api/v1/companies/nvidia/five-year-financials
export FINFLOW_GLOBAL_TAX_API_URL=http://demo-api:8011/api/v1/tax/2026-policy-trends
export FINFLOW_DEMO_DATABASE_URL=jdbc:postgresql://postgres:5432/finflow
export FINFLOW_DEMO_DATABASE_USERNAME=finflow
```

Database manifests store only `env:FINFLOW_DEMO_DATABASE_PASSWORD`; the real password stays in the server-side `.env` file. Re-importing a case refreshes existing connection settings, so environment changes take effect without recreating the project.

## Validate without importing

```bash
python3 examples/cases/import_cases.py --check
```
