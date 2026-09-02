# FinFlow curated demo cases

This directory contains four portable demo cases. The first three are full showcase projects; Qiming is retained as the original fictional operating-analysis case.

- `nvidia-devices`: NVIDIA device portfolio, workload fit, TCO and deployment-roadmap analysis;
- `nvidia`: NVIDIA FY2022-FY2026 operating and financial-quality analysis;
- `global-tax-2026`: 2026 global tax-policy trends, simulated entity exposure and action planning;
- `qiming`: fictional operating and capital-market analysis for Qiming Manufacturing.

No API key, database password, private document or local absolute path is included. All device benchmarks, cost scenarios, quarterly splits, entity exposure and cash-impact scenarios marked as simulated are demonstration data.

## Included capabilities

The showcase projects combine PostgreSQL, streaming data-service APIs, CSV, Markdown, PDF, Word and official web references. Their workflows include transparent DuckDB SQL, Ref retrieval, AI analysis, citation-enabled interactive reports, PowerPoint, HTML slides, editable Word output, Mermaid or Excalidraw diagrams. They intentionally omit the manual-review node.

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

Import all cases through the public FinFlow API:

```bash
python3 examples/cases/import_cases.py --api http://127.0.0.1:8080
```

Import one or more cases by repeating `--case`:

```bash
python3 examples/cases/import_cases.py --case nvidia-devices --case global-tax-2026
```

The importer is idempotent for projects, source files, connections and starter deliverables. Re-running it updates each project's workflow to the bundled definition.

For direct local development, `scripts/start-local.sh` also starts the demo data API on port `8011`. Load `.env` before importing so the four API connections use `127.0.0.1`; Docker Compose keeps the default `demo-api:8011` service address.

## Offline deployment

The offline image bundle must contain `finflow-demo-api:1.0.0` in addition to the main FinFlow images. On the restricted server, load the images and start with `--no-build`:

```bash
docker compose -f docker-compose.yml -f examples/cases/docker-compose.cases.yml up -d --no-build
```

The importer uses only the Python standard library and requires no package installation.

## Optional endpoint overrides

```bash
export FINFLOW_NVIDIA_API_URL=http://demo-api:8011/api/v1/companies/nvidia/five-year-financials
export FINFLOW_NVIDIA_PRODUCT_API_URL=http://demo-api:8011/api/v1/companies/nvidia/product-portfolio
export FINFLOW_GLOBAL_TAX_API_URL=http://demo-api:8011/api/v1/tax/2026-policy-trends
export FINFLOW_QIMING_MARKET_API_URL='http://demo-api:8011/api/v1/market/monthly-indicators?year=2026'
export FINFLOW_DEMO_DATABASE_URL=jdbc:postgresql://postgres:5432/finflow
export FINFLOW_DEMO_DATABASE_USERNAME=finflow
```

Database manifests store only `env:FINFLOW_DEMO_DATABASE_PASSWORD`; the real password stays in the server-side `.env` file. Re-importing a case refreshes existing connection settings, so environment changes take effect without recreating the project.

## Validate without importing

```bash
python3 examples/cases/import_cases.py --check
```
