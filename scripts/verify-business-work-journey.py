"""Read-only checks for a work submitted through the platform UI."""
import argparse
import json
import os
from pathlib import Path

import httpx
from dotenv import load_dotenv


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", default="美国选举与社会影响")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    load_dotenv(Path(__file__).resolve().parents[1] / ".env")
    with httpx.Client(base_url="http://127.0.0.1:8080", trust_env=False, timeout=60) as client:
        client.post("/api/auth/login", json={"username": os.getenv("FINFLOW_AUTH_DEFAULT_USERNAME", "default"),
                    "password": os.getenv("FINFLOW_AUTH_DEFAULT_PASSWORD", "Pr0d1234")}).raise_for_status()

        def get(path):
            response = client.get(path)
            response.raise_for_status()
            return response.json()

        projects = [p for p in get("/api/projects") if p["name"] == args.project]
        assert len(projects) == 1, "Project name must be unique"
        project = projects[0]
        works = get(f"/api/projects/{project['id']}/works")
        if not works:
            print("No work yet")
            return
        work = works[0]
        state = get(f"/api/projects/{project['id']}/works/{work['sessionId']}/implementation")
        definition = state.get("definition")
        latest = next(iter(state["runs"]), None)
        if args.check:
            assert definition and definition["verified"], "Implementation has not executed successfully"
            assert latest and latest["status"] == "SUCCEEDED", "Execution incomplete"
            groups = definition["implementation"]["businessStages"]
            steps = definition["implementation"]["steps"]
            assert groups and len(groups) < len(steps), "Journey must exercise many-to-one grouping"
            assert [s for g in groups for s in g["stepIds"]] == [s["id"] for s in steps]
            assert len(latest["businessStages"]) == len(groups)
            for stage in latest["businessStages"]:
                assert stage["status"] == "SUCCEEDED"
                primary = [o for o in stage["outputs"] if o.get("role") == "PRIMARY"]
                assert primary, "Missing primary result"
                for output in primary:
                    # All journey primary outcomes are native documents or source snapshots.
                    preview = get(f"/api/files/{output['resourceId']}/preview")
                    assert preview["kind"] in ("text", "research", "document"), preview["kind"]
        print(json.dumps({"projectId": project["id"], "sessionId": work["sessionId"], "status": work["status"],
                          "summary": work.get("resultSummary"), "definitionVersion": definition and definition["version"],
                          "definitionHash": definition and definition["hash"], "run": latest,
                          "businessContract": definition and definition["implementation"].get("businessStages")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
