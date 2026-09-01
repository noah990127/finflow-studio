# FinFlow curated demo cases

This directory contains exactly two portable demo cases:

- `nvidia`: NVIDIA FY2022-FY2026 public financial analysis;
- `qiming`: a fully simulated operating and capital-market analysis for Qiming Manufacturing.

No API key, database password, private document, or local absolute path is included. Qiming Manufacturing and all of its business data are fictional.

## Included resources

Each case contains a manifest, source files, a workflow definition, sanitized connection definitions, and deterministic starter deliverables. The shared `demo-api` serves the simulated market indicators and the curated NVIDIA five-year dataset.

Source and usage notes are documented in `nvidia/README.md` and `qiming/README.md`.

## Start with Docker Compose

Build or load the regular FinFlow images, then start FinFlow with the demo API overlay:

```bash
docker compose -f docker-compose.yml -f examples/cases/docker-compose.cases.yml up -d
```

Initialize the simulated Qiming schema in the FinFlow PostgreSQL container:

```bash
docker compose exec -T postgres psql -U finflow -d finflow < examples/cases/qiming/postgres-init.sql
```

Import both cases through the FinFlow API:

```bash
python3 examples/cases/import_cases.py --api http://127.0.0.1:8080
```

The importer is idempotent for projects, source files, data connections, and starter deliverables. Re-running it updates the project workflow to the bundled definition.

## Offline deployment

The offline image bundle must also contain `finflow-demo-api:1.0.0`. Build it on the connected build machine for the target server architecture, then include it in `docker save` with the other FinFlow images.

On the restricted server, start with `--no-build` after loading the image bundle:

```bash
docker compose -f docker-compose.yml -f examples/cases/docker-compose.cases.yml up -d --no-build
```

The importer runs with the Python standard library and needs no package installation. Run it from the extracted deployment bundle after the Java API is healthy.

## Optional endpoint overrides

The default connection URLs work inside the supplied Docker Compose network. Override them before importing when the deployment uses different service names:

```bash
export FINFLOW_NVIDIA_API_URL=http://demo-api:8011/api/v1/companies/nvidia/five-year-financials
export FINFLOW_QIMING_MARKET_API_URL='http://demo-api:8011/api/v1/market/monthly-indicators?year=2026'
export FINFLOW_DEMO_DATABASE_URL=jdbc:postgresql://postgres:5432/finflow
export FINFLOW_DEMO_DATABASE_USERNAME=finflow
```

The Qiming database connection stores only `env:DATABASE_PASSWORD`. The real database password remains in the server-side FinFlow `.env` file.

## Validate without importing

```bash
python3 examples/cases/import_cases.py --check
```
