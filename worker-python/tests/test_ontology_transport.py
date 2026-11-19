"""Contract checks for the real 1.3 API shapes and ambiguous publication outcomes."""
import json

import httpx
import pytest
import yaml
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.tacit import ontology_transport as transport
from app.tacit.workspace_state import digest


PROJECT = "project-1"
TARGET = transport.Target("https://example.test", "e" * 64, "upstream-test-key")
SNAPSHOT = "f" * 64
DATABASE = f"finflow_{digest(PROJECT)[:16]}_{SNAPSHOT}"
TERM = f"订单 [ff:{SNAPSHOT}:{digest('orders')}]"


def model():
    return {
        "data_layer": {
            "databases": [{"id": "db", "dialect": "", "schemas": [{
                "id": "schema", "database_name": DATABASE, "name": "finflow_tables", "tables": [{
                    "id": "table", "name": "订单", "description": "已复核", "pk": ["id"], "type": "TABLE",
                    "columns": [{"id": "column", "name": "id", "description": "单号", "type": "INT",
                                 "sample_values": [], "is_nullable": False, "is_unique": True},
                                {"id": "parent", "name": "parent", "description": "父单号", "type": "INT",
                                 "sample_values": [], "is_nullable": True, "is_unique": False}],
                }],
            }]}], "foreign_keys": [{"source_column_id": "parent", "target_column_id": "column"}], "joins": [],
        },
        "semantic_layer": {
            "terms": [{"id": "term", "name": TERM, "description": "交易订单", "represents": ["table"],
                       "columns_attributes": [{"id": "attr", "column_id": "column", "name": "单号", "description": "订单编码"}]}],
            "semantic_fks": [], "sql_attributes": dict(manual=[], table=[], sql=[], bridge_table=[]), "custom_analyses": [],
        }, "zones": [],
    }


def live_document():
    # The importer allocates NEW live IDs. Its native exporter returns these IDs in all edges.
    def rewrite(row):
        if isinstance(row, list):
            return [rewrite(r) for r in row]
        if not isinstance(row, dict):
            return row
        out = {}
        for k, v in row.items():
            if k == "represents":
                out[k] = ["live-" + r for r in v]
            elif k in ("id", "source_column_id", "target_column_id", "column_id"):
                out[k] = "live-" + v
            else:
                out[k] = rewrite(v)
        return out
    return rewrite(model())


class Gateway:
    def __init__(self):
        self.document = None
        self.databases = []
        self.terms = []
        self.write_count = 0
        self.urls = []
        self.import_response = None
        self.details = None
        self.fail_after_commit = False

    def committed(self):
        self.document = live_document()
        self.databases = [{"name": DATABASE, "id": "live-db"}]
        self.terms = [{"name": TERM, "description": "交易订单", "id": "live-term"}]
        self.details = {"id": "live-term", "name": TERM, "description": "交易订单", "table_count": 1,
                        "tables": [{"id": "live-table", "db_id": "live-db"}]}

    def receive(self, req):
        self.urls.append(req.url.path)
        assert req.headers["x-api-key"] == TARGET.token
        assert req.headers.get("authorization") is None
        if req.url.path == "/api/datasources/dbs":
            return httpx.Response(200, json={"count": len(self.databases), "data": self.databases})
        if req.url.path == "/api/terms":
            assert dict(req.url.params) == {"query": "[ff:" + SNAPSHOT + ":", "skip": "0", "limit": "100"}
            return httpx.Response(200, json={"terms": self.terms, "total": len(self.terms), "related_counts": [],
                                  "column_attribute_counts": [{"term_id": "live-term", "count": 1}] if self.terms else [],
                                  "sql_attribute_counts": []})
        if req.url.path == "/api/model/import":
            self.write_count += 1
            assert dict(req.url.params) == {"replace": "false", "embed": "false"}
            assert 'name="file"; filename="finflow-model.yaml"' in req.content.decode()
            assert req.headers["content-type"].startswith("multipart/form-data;")
            counts = transport.project_model(model(), PROJECT).counts
            counts = {k: counts[k] for k in transport.COUNT_KEYS}
            self.committed()
            if self.fail_after_commit:
                raise httpx.ReadTimeout("private contents from gateway")
            return self.import_response or httpx.Response(200, json={"success": True, "summary": {
                "format": "auto_ontology", "database_ids": ["live-db"], "replace": False, "semantic_fks": 0,
                "terms": 1, "column_attributes": 1, "sql_attributes": 0, "custom_analyses": 0,
                "created": counts, "skipped": dict.fromkeys(counts, 0),
            }})
        if req.url.path == "/api/model/export":
            assert json.loads(req.content) == {"databases": ["live-db"], "format": "auto_ontology", "include_ontology": False}
            body = yaml.safe_dump(self.document, allow_unicode=True) if isinstance(self.document, dict) else self.document
            return httpx.Response(200, content=body, headers={"Content-Type": "application/x-yaml"})
        assert req.url.path == "/api/terms/live-term"
        return httpx.Response(200, json={"data": self.details})


async def publish(remote, verify=False, doc=None, patch=None):
    doc = model() if doc is None else doc
    data = {"project_id": PROJECT, "target_id": TARGET.target_id, "digest": digest(doc), "model": doc,
            "verify_only": verify, **(patch or {})}
    async with httpx.AsyncClient(base_url=TARGET.base_url, headers={"x-api-key": TARGET.token},
                                 transport=httpx.MockTransport(remote.receive)) as client:
        return await transport.run_publish(transport.PublishRequest(**data), TARGET, client)


@pytest.mark.asyncio
async def test_publish_then_read_only_reconcile():
    remote = Gateway()
    assert (await publish(remote, True))["status"] == "ABSENT" and remote.write_count == 0
    result = await publish(remote)
    assert result["status"] == "VERIFIED" and result["receipt"]["mode"] == "imported"
    assert result["receipt"]["database_id"] == "live-db" and result["receipt"]["counts"]["foreign_keys"] == 1
    assert (await publish(remote))["receipt"]["mode"] == "reconciled"
    assert (await publish(remote, True))["receipt"]["digest"] == digest(model())
    assert remote.write_count == 1


@pytest.mark.asyncio
async def test_committed_then_timeout_or_malformed_reply_reconciles_without_second_write():
    for invalid_summary in (None, {"success": False}, {"success": True, "summary": {"replace": True}}):
        remote = Gateway()
        remote.fail_after_commit = invalid_summary is None
        if invalid_summary:
            remote.import_response = httpx.Response(200, json=invalid_summary)
        result = await publish(remote)
        assert result["status"] == "UNCERTAIN" and "private" not in str(result)
        assert (await publish(remote, True))["status"] == "VERIFIED" and remote.write_count == 1


@pytest.mark.parametrize("mutation", [
    "column_description", "foreign_key", "attribute_link", "foreign_catalog",
    "extra_term", "missing_term", "outside_represents", "orphan_term", "duplicate_key_yaml", "alias_yaml",
])
@pytest.mark.asyncio
async def test_drift_is_never_acknowledged_or_overwritten(mutation):
    remote = Gateway()
    remote.committed()
    data = remote.document["data_layer"]
    term = remote.document["semantic_layer"]["terms"][0]
    if mutation == "column_description":
        data["databases"][0]["schemas"][0]["tables"][0]["columns"][0]["description"] += "different"
    elif mutation == "foreign_key":
        data["foreign_keys"][0]["source_column_id"] = "live-column"
    elif mutation == "attribute_link":
        term["columns_attributes"][0]["column_id"] = "live-parent"
    elif mutation == "foreign_catalog":
        remote.databases.append({"id": "customer-b", "name": "private-foreign-db"})
    elif mutation == "extra_term":
        remote.terms.append({"id": "duplicate-term", "name": TERM, "description": "private-foreign-term"})
    elif mutation == "missing_term":
        remote.terms.clear()
    elif mutation == "outside_represents":
        remote.details["tables"].append({"id": "outside", "db_id": "private-foreign-db"})
        remote.details["table_count"] += 1
    elif mutation == "orphan_term":
        remote.databases.clear()
    elif mutation == "duplicate_key_yaml":
        remote.document = "data_layer: {}\ndata_layer: {}"
    elif mutation == "alias_yaml":
        remote.document = "items: &a [1, 2]\nrepeated: *a"
    result = await publish(remote)
    assert result["status"] == "UNCERTAIN" and remote.write_count == 0
    assert "private-foreign" not in str(result) and TARGET.token not in str(result)


@pytest.mark.asyncio
async def test_validate_before_any_network():
    remote = Gateway()
    assert (await publish(remote, patch={"target_id": "a" * 64}))["status"] == "FAILED"
    assert (await publish(remote, patch={"digest": "0" * 64}))["status"] == "FAILED"
    assert (await publish(remote, patch={"project_id": "other"}))["status"] == "FAILED"
    doc = model()
    doc["semantic_layer"]["custom_analyses"] = [{"sql": "unreviewed SQL"}]
    assert (await publish(remote, doc=doc))["status"] == "FAILED"
    assert not remote.urls


def test_project_binding_secret_origin_and_private_response(tmp_path, monkeypatch):
    cfg = tmp_path / "targets.json"
    alias = {"base_url": TARGET.base_url, "target_id": "publisher-one", "token_env": "PROJECT_ONE_KEY"}
    cfg.write_text(json.dumps({PROJECT: alias}))
    monkeypatch.setenv(transport.CONFIG_ENV, str(cfg))
    monkeypatch.setenv("PROJECT_ONE_KEY", "test-publisher-token")
    monkeypatch.setenv(transport.SECRET_ENV, "a" * 32)
    app = FastAPI()
    app.include_router(transport.router)
    client = TestClient(app)
    path = "/tacit/ontology/target"
    params = {"json": {"project_id": PROJECT}, "headers": {"X-FinFlow-Ontology-Secret": "a" * 32}}
    assert client.post(path, json={"project_id": PROJECT}).status_code == 403
    original = client.post(path, **params).json()
    assert original["configured"] is True and set(original) == {"configured", "target_id", "target_label"}
    assert original["target_label"] == "publisher-one"
    assert transport.SAFE_ID.fullmatch(original["target_id"]) and "token" not in str(original)
    assert client.post(path, json={"project_id": PROJECT, "base_url": TARGET.base_url},
                       headers=params["headers"]).status_code == 422
    monkeypatch.setenv("PROJECT_ONE_KEY", "rotated-key")
    assert client.post(path, **params).json() == original
    for duplicate in [dict(alias, base_url=TARGET.base_url.upper() + ":443/", target_id="different"),
                      dict(alias, base_url="https://different.example")]:
        cfg.write_text(json.dumps({PROJECT: alias, "foreign": duplicate}))
        assert client.post(path, **params).json() == {"configured": False, "target_id": None}
    for url in ["http://other.example", "https://user:password@example.test", "https://example.test?token=123",
                "https://example.test/api", "//example.test"]:
        cfg.write_text(json.dumps({PROJECT: dict(alias, base_url=url)}))
        assert client.post(path, **params).json()["configured"] is False
    cfg.write_text(json.dumps({PROJECT: dict(alias, base_url="http://127.0.0.1:17771")}))
    result = client.post(path, **params).json()
    assert result["configured"] is True and result["target_id"] != original["target_id"]
    monkeypatch.setenv("FINFLOW_AUTO_ONTOLOGY_CA_FILE", str(tmp_path / "missing-ca.pem"))
    assert client.post(path, **params).json()["configured"] is False
    invalid_ca = tmp_path / "invalid.pem"
    invalid_ca.write_text("this is not a CA certificate")
    monkeypatch.setenv("FINFLOW_AUTO_ONTOLOGY_CA_FILE", str(invalid_ca))
    assert client.post(path, **params).json()["configured"] is False


@pytest.mark.asyncio
async def test_rejected_write_vs_failed_post_commit_readback():
    remote = Gateway()
    receive = remote.receive

    def reject_write(req):
        if req.url.path == "/api/model/import":
            return httpx.Response(403, json={"message": "private upstream response"})
        return receive(req)

    remote.receive = reject_write
    result = await publish(remote)
    assert result["status"] == "FAILED" and "private" not in str(result)

    def reject_readback(req):
        if req.url.path == "/api/model/export":
            return httpx.Response(403, json={"message": "private upstream response"})
        return receive(req)

    remote.receive = reject_readback
    result = await publish(remote)
    assert result["status"] == "UNCERTAIN" and "private" not in str(result)


@pytest.mark.asyncio
@pytest.mark.parametrize("response", [
    httpx.Response(302, headers={"location": "https://other.example/private"}),
    httpx.Response(200, content=b"login", headers={"content-type": "text/html"}),
    httpx.Response(200, content=b'{"count":0,"data":[],"data":[]} ', headers={"content-type": "application/json"}),
    httpx.Response(200, content=b"x" * (1 + transport.RESPONSE_LIMIT), headers={"content-type": "application/json"}),
])
async def test_bounded_readback_rejects_redirects_and_invalid_content(response):
    remote = Gateway()
    remote.receive = lambda req: response
    result = await publish(remote, True)
    assert result["status"] == "UNCERTAIN" and "example" not in str(result)


@pytest.mark.asyncio
async def test_multiple_glossary_pages_exclude_other_snapshots_counts():
    urls = []
    def page(req):
        urls.append(dict(req.url.params))
        skip, size = int(req.url.params["skip"]), int(req.url.params["limit"])
        records = [{"id": f"term-{i}", "name": f"item-{i}", "description": ""}
                   for i in range(skip, min(101, skip + size))]
        return httpx.Response(200, json={"terms": records, "total": 101, "column_attribute_counts": [
            {"term_id": r["id"], "count": 1} for r in records], "sql_attribute_counts": [], "related_counts": []})
    async with httpx.AsyncClient(base_url=TARGET.base_url, transport=httpx.MockTransport(page)) as client:
        terms, attrs, sql = await transport.list_snapshot_terms(client, SNAPSHOT)
    assert len(terms) == len(attrs) == 101 and not sql
    assert [url["skip"] for url in urls] == ["0", "100"]
