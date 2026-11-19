r"""Verify procurement publication against an empty, dedicated NVIDIA Auto Ontology gateway.

Build the Java backend jar, install the worker's development dependencies, and supply
FINFLOW_AUTO_ONTOLOGY_API_TOKEN. Example from the repository root:

    worker-python/.venv312/bin/python scripts/verify-ontology-publication.py \
        --target http://127.0.0.1:3000 --backend-jar backend-java/target/finbtp-backend-0.1.0-SNAPSHOT.jar

Creates one test catalog in the supplied target. FinFlow uses a temporary H2 database by
default. For its PostgreSQL path, pass --postgres and set DATABASE_URL (JDBC URL),
DATABASE_USERNAME and DATABASE_PASSWORD for a separately provisioned disposable database.
The deterministic extraction fixture serves only the model response. Material parsing,
revision checks, human reviews, mapping, publication, and remote reads use real APIs.
"""
import argparse
import asyncio
from contextlib import ExitStack, contextmanager
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import runpy
import secrets
import socket
import subprocess
import sys
import tempfile
from threading import Thread
import time
import uuid

import httpx
import yaml
from rdflib import Graph, OWL, RDF, RDFS


ROOT = Path(__file__).resolve().parents[1]
WORKER = ROOT / "worker-python"
TOKEN_ENV = "FINFLOW_AUTO_ONTOLOGY_API_TOKEN"


def port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def http(client, method, path, status=200, **kwargs):
    response = client.request(method, path, **kwargs)
    if response.status_code != status:
        raise AssertionError(f"{method} {path}: expected {status}, got {response.status_code}: {response.text[:300]}")
    return response.json() if response.is_success else None


def native_digest(model):
    encoded = json.dumps(model, sort_keys=True, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode()
    return hashlib.sha256(encoded).hexdigest()


@contextmanager
def extraction_fixture(fixture):
    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            if self.path != "/v1/responses":
                self.send_error(404)
                return
            req = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            prompt = json.loads(req["input"])
            output = "{}"
            if isinstance(prompt, dict) and "passages" in prompt:
                output = asyncio.run(fixture["FixtureModel"]().complete(req["instructions"], req["input"]))
            data = json.dumps({"output_text": output}, ensure_ascii=False).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, *args):
            pass

    with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            yield f"http://127.0.0.1:{server.server_port}/v1"
        finally:
            server.shutdown()
            thread.join()


@contextmanager
def service(cmd, cwd, env, log, health):
    with log.open("w") as output:
        proc = subprocess.Popen(cmd, cwd=cwd, stdout=output, stderr=subprocess.STDOUT, env=env)
        try:
            with httpx.Client(trust_env=False, timeout=2) as client:
                for _ in range(240):
                    if proc.poll() is not None:
                        raise RuntimeError(f"{cmd[0]} stopped with code {proc.returncode} before {health} was ready")
                    try:
                        if client.get(health).status_code == 200:
                            break
                    except httpx.TransportError:
                        pass
                    time.sleep(.25)
                else:
                    raise TimeoutError(f"{health} startup timed out")
            yield
        finally:
            proc.terminate()
            try:
                proc.wait(10)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait()


def upload(client, project_path, text, existing=None):
    file = http(client, "POST", project_path + "/files", 202, params={"resourceId": existing} if existing else {},
                files={"file": ("采购登记.txt", text.encode(), "text/plain")})
    for _ in range(150):
        parsed = http(client, "GET", "/api/files/" + file["id"])
        if parsed["parseStatus"] == "READY":
            return file["id"]
        time.sleep(.1)
    raise TimeoutError("Uploaded policy did not parse")


def review_material(client, tacit, file_id, target_id):
    state = http(client, "GET", tacit)

    def command(action, payload=None):
        nonlocal state
        body = {"action": action, "request_id": str(uuid.uuid4()), "expected_revision": state["revision"],
                "payload": payload or {}}
        response = http(client, "POST", tacit + "/commands", json=body)
        state = response["workspace"]
        return response["result"]

    command("select_sources", {"source_ids": [file_id]})
    command("save_batch", {"section": "scope", "scope": {"goal": "核验采购登记流程", "in_scope": ["采购单"]}})
    command("extract", {"source_id": file_id, "offset": 0})
    assert state["issues"] == [] and len(state["spec"]["rules"]) == 1
    rule_id = state["spec"]["rules"][0]["rule_id"]
    command("confirm_item", {"section": "rules", "item_id": rule_id, "text": "采购登记规则已经核对。"})
    command("finalize", {"completion": "complete"})
    command("generate_flow")
    command("review_flow", {"decision": "approved", "comment": "按原文核对收单、登记与回执。"})
    command("generate_cqs")
    question = state["artifacts"]["cqs"]["content"][0]
    command("review_cq", {"cq_id": question["cq_id"], "decision": "accepted"})
    command("build_candidate")
    command("review_candidate", {"decision": "approved", "comment": "候选与当前登记规程一致。"})
    command("build_ontology_model")
    unchecked = state["artifacts"]["ontology_model"]["content"]
    assert unchecked["quality"]["publication_ready"] is False
    http(client, "POST", tacit + "/ontology/publications", 409, json={
        "request_id": str(uuid.uuid4()), "expected_revision": state["revision"],
        "target_id": target_id, "model_digest": unchecked["digest"],
    })
    command("validate_ontology")
    scenarios = state["artifacts"]["walkthrough"]["content"]["processes"][0]["scenarios"]
    assert any(row["status"] == "path_verified" for row in scenarios)
    result = command("build_ontology_model")
    artifact = state["artifacts"]["ontology_model"]
    assert artifact["status"] == "ready"
    bundle = artifact["content"]
    assert bundle["quality"]["publication_ready"] is True
    assert file_id in bundle["manifest"]["source_versions"]
    assert native_digest(bundle["model"]) == result["model_digest"] == bundle["digest"]
    assert bundle["counts"]["logical_objects"] == bundle["counts"]["terms"] == 1
    assert bundle["counts"]["competency_questions"] >= 1 and bundle["counts"]["manifest_only"] >= 1
    graph = Graph().parse(data=bundle["ontology_turtle"], format="turtle")
    labels = {str(label) for cls in graph.subjects(RDF.type, OWL.Class) for label in graph.objects(cls, RDFS.label)}
    assert labels == {"采购单"}, labels
    return state["revision"], bundle


def bind(config, project_id, target_url, token_env):
    pending = config.with_suffix(".tmp")
    pending.write_text(json.dumps({project_id: {"base_url": target_url, "target_id": "acceptance",
                                                "token_env": token_env}}))
    pending.replace(config)


def read_remote(target, bundle, receipt):
    databases = http(target, "GET", "/api/datasources/dbs")
    assert databases["count"] == len(databases["data"]) == 1
    database = databases["data"][0]
    assert (database["name"], database["id"]) == (bundle["database_name"], receipt["database_id"])
    query = {"format": "auto_ontology", "databases": [database["id"]], "include_ontology": False}
    response = target.post("/api/model/export", json=query)
    response.raise_for_status()
    exported = yaml.safe_load(response.content)
    table = exported["data_layer"]["databases"][0]["schemas"][0]["tables"][0]
    term = exported["semantic_layer"]["terms"][0]
    assert table["name"] == "采购单" and table["columns"][0]["name"] == "采购单号"
    assert term["represents"] == [table["id"]]
    attribute = term["columns_attributes"][0]
    assert (attribute["name"], attribute["column_id"]) == ("采购单号", table["columns"][0]["id"])
    glossary = http(target, "GET", "/api/terms", params={"query": term["name"]})
    assert glossary["total"] == 1 and glossary["terms"][0]["id"] == term["id"]
    return {"database_id": database["id"], "term_id": term["id"], "table": table["name"], "field": attribute["name"]}


def verify(client, target, target_url, config, password, policy):
    started = time.monotonic()
    empty = http(target, "GET", "/api/datasources/dbs")
    if empty["count"] != 0 or empty["data"]:
        raise ValueError("Acceptance requires an empty dedicated target")
    http(client, "GET", "/api/projects", 401)
    http(client, "POST", "/api/auth/login", json={"username": "default", "password": password})
    project = http(client, "POST", "/api/projects", 201, json={"name": "采购登记本体验收"})["id"]
    project_path = "/api/projects/" + project
    tacit = project_path + "/tacit"
    publications = tacit + "/ontology/publications"
    file_id = upload(client, project_path, policy)
    bind(config, project, target_url, TOKEN_ENV)
    target_id = http(client, "GET", publications)["target"]["target_id"]
    revision, bundle = review_material(client, tacit, file_id, target_id)
    config.write_text("{}")
    request = {"request_id": str(uuid.uuid4()), "expected_revision": revision, "model_digest": bundle["digest"],
               "target_id": "0" * 64}
    assert http(client, "GET", publications)["target"]["configured"] is False
    http(client, "POST", publications, 409, json=request)
    bind(config, project, target_url, TOKEN_ENV)
    assert http(client, "GET", publications)["target"]["target_id"] == target_id
    http(client, "POST", publications, 409, json=request)
    request["target_id"] = target_id
    http(client, "POST", publications, 409, json={**request, "expected_revision": revision - 1})
    assert http(client, "GET", publications)["items"] == []
    assert http(target, "GET", "/api/datasources/dbs")["count"] == 0

    # The token is rotated within the same administrator binding to exercise reconciliation.
    bind(config, project, target_url, "ONTOLOGY_ACCEPTANCE_INVALID_KEY")
    pending = http(client, "POST", publications, json=request)
    assert pending["status"] == "UNCERTAIN", pending
    bind(config, project, target_url, TOKEN_ENV)
    row_path = publications + "/" + pending["id"]
    absent = http(client, "POST", row_path + "/retry", json={"expected_revision": revision})
    assert absent["status"] == "ABSENT" and http(target, "GET", "/api/datasources/dbs")["count"] == 0
    result = http(client, "POST", row_path + "/retry", json={"expected_revision": revision})
    assert result["status"] == "VERIFIED", result
    receipt = result["receipt"]
    assert receipt["verified"] and receipt["digest"] == bundle["digest"]
    assert http(client, "GET", row_path + "/snapshot") == bundle
    assert http(client, "POST", publications, json=request)["id"] == pending["id"]
    replay = http(client, "POST", publications, json={**request, "request_id": str(uuid.uuid4())})
    assert replay["id"] == pending["id"] and replay["status"] == "VERIFIED"
    assert len(http(client, "GET", publications)["items"]) == 1
    assert http(client, "GET", tacit)["revision"] == revision
    live = read_remote(target, bundle, receipt)

    upload(client, project_path, policy + "另有例外事项待核对。", file_id)
    stale = http(client, "GET", tacit)
    assert stale["revision"] > revision and stale["artifacts"]["ontology_model"]["status"] == "stale"
    for expected_revision in (revision, stale["revision"]):
        http(client, "POST", publications, 409, json={**request, "expected_revision": expected_revision,
                                                     "request_id": str(uuid.uuid4())})
    assert http(client, "GET", row_path + "/snapshot") == bundle
    assert live == read_remote(target, bundle, receipt)
    return {"project_id": project, "publication_id": pending["id"], "reviewed_revision": revision,
            "stale_revision": stale["revision"], "model_digest": bundle["digest"], "model_counts": bundle["counts"],
            "quality": bundle["quality"], "receipt_counts": receipt["counts"], "readback": live,
            "seconds": round(time.monotonic() - started, 2)}


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--target", required=True, help="Empty dedicated NVIDIA Auto Ontology gateway origin")
    p.add_argument("--java", default="java")
    p.add_argument("--postgres", action="store_true", help="Use a disposable PostgreSQL FinFlow database from the environment")
    source = p.add_mutually_exclusive_group(required=True)
    source.add_argument("--backend-jar", type=Path)
    source.add_argument("--java-classpath", help="Compiled backend classes, resources and runtime dependencies")
    args = p.parse_args()
    if not os.environ.get(TOKEN_ENV):
        p.error(f"Set {TOKEN_ENV} to the dedicated gateway key")
    if args.postgres and (not os.getenv("DATABASE_URL", "").startswith("jdbc:postgresql://")
                          or not os.getenv("DATABASE_USERNAME") or not os.getenv("DATABASE_PASSWORD")):
        p.error("--postgres requires DATABASE_URL, DATABASE_USERNAME and DATABASE_PASSWORD")
    sys.path.insert(0, str(WORKER))
    fixture = runpy.run_path(str(WORKER / "tests" / "test_tacit_lifecycle.py"))
    with tempfile.TemporaryDirectory(prefix="finflow-ontology-acceptance-") as work, ExitStack() as stack:
        folder = Path(work)
        config = folder / "targets.json"
        config.write_text("{}")
        password = secrets.token_urlsafe(32)
        secret = secrets.token_urlsafe(40)
        model_url = stack.enter_context(extraction_fixture(fixture))
        worker_port = port()
        worker_url = f"http://127.0.0.1:{worker_port}"
        env = {**os.environ, "FINFLOW_AUTO_ONTOLOGY_WORKER_SECRET": secret}
        worker_env = {**env, "FINFLOW_AUTO_ONTOLOGY_TARGETS_FILE": str(config),
                      "ONTOLOGY_ACCEPTANCE_INVALID_KEY": secrets.token_urlsafe(30), "FINFLOW_LLM_PROVIDER": "codex",
                      "OPENAI_API_KEY": "fixture-loopback-only", "OPENAI_BASE_URL": model_url}
        stack.enter_context(service([sys.executable, "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1",
                                     "--port", str(worker_port)], WORKER, worker_env, folder / "worker.log", worker_url + "/health"))
        java_port = port()
        java_url = f"http://127.0.0.1:{java_port}"
        database = ({**{key: os.environ[key] for key in ("DATABASE_URL", "DATABASE_USERNAME", "DATABASE_PASSWORD")},
                     "DATABASE_DRIVER": "org.postgresql.Driver"} if args.postgres else
                    {"DATABASE_URL": "jdbc:h2:mem:ontology;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                     "DATABASE_DRIVER": "org.h2.Driver", "DATABASE_USERNAME": "sa", "DATABASE_PASSWORD": ""})
        java_env = {**env, "SERVER_PORT": str(java_port), "SERVER_ADDRESS": "127.0.0.1", "WORKER_BASE_URL": worker_url,
                    **database,
                    "FINFLOW_STORAGE_PROVIDER": "local", "FINFLOW_STORAGE_ROOT": str(folder / "storage"),
                    "FINFLOW_AUTH_DEFAULT_USERNAME": "default", "FINFLOW_AUTH_DEFAULT_PASSWORD": password}
        java_env.pop(TOKEN_ENV, None)
        cmd = [args.java, "-jar", str(args.backend_jar.resolve())] if args.backend_jar else [
            args.java, "-cp", args.java_classpath, "com.finflow.studio.FinBtpStudioApplication"]
        stack.enter_context(service(cmd, folder, java_env, folder / "java.log", java_url + "/actuator/health"))
        target = stack.enter_context(httpx.Client(base_url=args.target, headers={"x-api-key": os.environ[TOKEN_ENV]},
                                                  trust_env=False, timeout=30))
        client = stack.enter_context(httpx.Client(base_url=java_url, trust_env=False, timeout=170))
        metrics = verify(client, target, args.target, config, password, fixture["POLICY"])
        metrics["finflow_database"] = "postgresql" if args.postgres else "h2"
        print(json.dumps(metrics, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
