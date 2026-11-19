"""Synthetic read-only reporting portal for browser journey verification."""
import csv
import io
from datetime import date
from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.responses import HTMLResponse, Response

app = FastAPI()
DATA = [
    {"date": f"2026-{month:02}-{day:02}", "department": department, "amount": amount}
    for month, day, department, amount in [
        (10, 3, "研发", 1200), (10, 8, "销售", 2400), (10, 18, "运营", 3600),
        (11, 2, "研发", 1500), (11, 6, "销售", 2500), (11, 12, "运营", 4000),
    ]
]


def selected(start: str, end: str):
    try:
        if date.fromisoformat(start) > date.fromisoformat(end):
            raise ValueError()
    except ValueError as exc:
        raise HTTPException(400, "日期范围无效") from exc
    return [r for r in DATA if start <= r["date"] <= end]


@app.get("/", response_class=HTMLResponse)
def page():
    return (Path(__file__).with_name("index.html")).read_text()


@app.get("/reports")
def reports(start: str, end: str):
    rows = selected(start, end)
    return {"rows": rows, "total": sum(r["amount"] for r in rows)}


@app.get("/export")
def export(start: str, end: str):
    stream = io.StringIO(); writer = csv.DictWriter(stream, fieldnames=["date", "department", "amount"])
    writer.writeheader(); writer.writerows(selected(start, end))
    return Response(stream.getvalue().encode("utf-8-sig"), media_type="text/csv",
                    headers={"Content-Disposition": 'attachment; filename="expense-report.csv"'})
