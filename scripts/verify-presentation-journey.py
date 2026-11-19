"""Read-only acceptance checks AFTER submitting a task through the platform UI.

Does not create projects, publish implementations, generate or import outputs.
Downloads the platform-produced PPT and renders it for visual inspection.
"""
import argparse
import hashlib
import io
import json
import os
import sys
from pathlib import Path

import httpx
from dotenv import load_dotenv
from PIL import Image
from pptx import Presentation

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "worker-python"))
from app.artifacts.presentation_review import render_pages, text_coverage
from pypdf import PdfReader


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", default="企业汇报体验验证")
    parser.add_argument("--inspect", action="store_true")
    parser.add_argument("--label", default="initial")
    parser.add_argument("--template", default="presenton-executive-v1")
    args = parser.parse_args()
    load_dotenv(ROOT / ".env")
    with httpx.Client(base_url="http://127.0.0.1:8080", timeout=180, trust_env=False) as client:
        client.post("/api/auth/login", json={
            "username": os.getenv("FINFLOW_AUTH_DEFAULT_USERNAME", "default"),
            "password": os.getenv("FINFLOW_TEST_PASSWORD", os.getenv("FINFLOW_AUTH_DEFAULT_PASSWORD", "Pr0d1234")),
        }).raise_for_status()
        def get(path):
            response = client.get(path)
            response.raise_for_status()
            return response.json()
        projects = [p for p in get("/api/projects") if p["name"] == args.project]
        assert len(projects) == 1, "Project must be unambiguous"
        project = projects[0]
        works = get(f"/api/projects/{project['id']}/works")
        assert len(works) == 1, "Expected one UI-created verification work"
        work = works[0]
        state = get(f"/api/projects/{project['id']}/works/{work['sessionId']}/implementation")
        outputs = get(f"/api/projects/{project['id']}/deliverables")
        if args.inspect:
            print(json.dumps({"project": project["id"], "work": work, "state": state, "outputs": outputs}, ensure_ascii=False))
            return
        assert state["definition"]["verified"], "Fixed implementation not verified"
        assert state["runs"][0]["status"] == "SUCCEEDED", "Latest execution did not succeed"
        ppt = sorted([p for p in outputs if p["format"] == "pptx"], key=lambda p: p["createdAt"])[-1]
        response = client.get(f"/api/deliverables/{ppt['id']}/download")
        response.raise_for_status()
        content = response.content
        validation = get(f"/api/deliverables/{ppt['id']}/validation")
        deck = Presentation(io.BytesIO(content))
        assert deck.core_properties.subject == args.template
        directory = ROOT / "artifacts/presentation-journey" / args.label
        directory.mkdir(parents=True, exist_ok=True)
        (directory / "新员工培养计划.pptx").write_bytes(content)
        pdf, images = render_pages(content, directory)
        texts = ["\n".join(s.text for s in slide.shapes if getattr(s, "has_text_frame", False)) for slide in deck.slides]
        actual = [p.extract_text() or "" for p in PdfReader(pdf).pages]
        assert len(images) == len(texts) == len(actual)
        coverage = [text_coverage(a, b) for a, b in zip(texts, actual)]
        assert min(coverage) >= .9, coverage
        assert not any(word in "\n".join(texts) for word in ("佐证", "SMART TASK", "已完成入职"))
        if args.label == "repeated":
            baseline = json.loads((ROOT / "artifacts/presentation-journey/reviewed/verification.json").read_text())
            assert state["definition"]["hash"] == baseline["state"]["definition"]["hash"]
            assert state["runs"][0]["snapshot"]["outline/plan"]["version"] == 2
            assert ppt["id"] != baseline["output"]["id"]
            previous = client.get(f"/api/deliverables/{baseline['output']['id']}/download")
            previous.raise_for_status()
            assert hashlib.sha256(previous.content).hexdigest() == baseline["checksum"]
            assert len(texts) == 6
            assert "导师" in texts[3] and "反馈" in texts[3]
            assert "反馈提供者待确认" not in "\n".join(texts)
        sheet = Image.new("RGB", (1440, ((len(images) + 1) // 2) * 420), "#e6e8eb")
        for i, path in enumerate(images):
            with Image.open(path) as page:
                page.thumbnail((700, 394))
                sheet.paste(page, ((i % 2) * 720 + 10, (i // 2) * 420 + 10))
        sheet.save(directory / "overview.png")
        report = {"project": project, "work": work, "state": state, "output": ppt,
                  "output_count": len(outputs), "checksum": hashlib.sha256(content).hexdigest(),
                  "validation": validation, "slides": texts, "text_coverage": coverage}
        (directory / "verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
        print(json.dumps({"output": ppt, "pages": len(texts), "coverage": min(coverage),
                          "definitionHash": state["definition"]["hash"], "directory": str(directory)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
