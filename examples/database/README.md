# Finance database demo

`finance-demo.sql` creates an idempotent PostgreSQL demonstration dataset for the database browser and structured-data workflow. It contains related operating, budget, inventory, receivables and cash-flow tables plus a management summary view.

Run locally with:

```bash
psql -d finflow -f examples/database/finance-demo.sql
```

Run in Docker Compose with:

```bash
docker compose exec -T postgres psql -U finflow -d finflow < examples/database/finance-demo.sql
```
