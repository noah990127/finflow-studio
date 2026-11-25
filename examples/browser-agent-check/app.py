"""Loopback-only regression fixture. No external systems or real transactions."""
from pathlib import Path
from fastapi import FastAPI
from fastapi.responses import HTMLResponse
from pydantic import BaseModel, Field

app = FastAPI()
submissions = []


class Entry(BaseModel):
    name: str = Field(max_length=200)
    amount: str = Field(max_length=30)
    note: str = Field(max_length=2000)


@app.get("/", response_class=HTMLResponse)
def index():
    return Path(__file__).with_name("index.html").read_text()


@app.post("/submissions")
def submit(body: Entry):
    submissions.append(body.model_dump())
    return {"count": len(submissions)}


@app.get("/result")
def result():
    return {"submissions": submissions}
