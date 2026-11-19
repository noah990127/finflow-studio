"""Publish reviewed native models to a project's administrator-bound Auto Ontology 1.3 gateway."""
import asyncio
import hmac
import ipaddress
import json
import os
import re
import ssl
from copy import deepcopy
from dataclasses import dataclass, field
from pathlib import Path

import httpx
import yaml
from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel, ConfigDict, Field

from .ontology_limits import MODEL_LIMIT, TERM_LIMIT, model_bytes
from .workspace_state import digest

router = APIRouter(prefix="/tacit/ontology")
CONFIG_ENV = "FINFLOW_AUTO_ONTOLOGY_TARGETS_FILE"
SECRET_ENV = "FINFLOW_AUTO_ONTOLOGY_WORKER_SECRET"
# The native exporter escapes Unicode and indents nested YAML. Allow for the
# expansion of a valid 2 MiB UTF-8 model while bounding every remote response.
RESPONSE_LIMIT = 8 * 1024 * 1024
COUNT_KEYS = {"databases", "schemas", "tables", "columns", "terms", "column_attributes", "sql_attributes", "custom_analyses"}
SAFE_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}")
HASH = r"[0-9a-f]{64}"


class Invalid(ValueError):
    """Invalid configuration, model or protocol; never return the invalid value."""


class Rejected(Exception):
    def __init__(self, status):
        self.status = status


def require(condition):
    if not condition:
        raise Invalid()


def unique_object(pairs):
    out = {}
    for key, value in pairs:
        require(key not in out)
        out[key] = value
    return out


@dataclass(frozen=True)
class Target:
    base_url: str
    target_id: str
    token: str = field(repr=False)
    label: str = ""
    tls: ssl.SSLContext | None = field(default=None, repr=False, compare=False)


def tls_context():
    context = ssl.create_default_context()
    ca_file = os.getenv("FINFLOW_AUTO_ONTOLOGY_CA_FILE")
    if ca_file:
        require(Path(ca_file).is_absolute() and Path(ca_file).is_file())
        context.load_verify_locations(cafile=ca_file)
    return context


def origin(value):
    require(isinstance(value, str) and value == value.strip() and not re.search(r"[\s\\?#@]", value))
    url = httpx.URL(value)
    require(url.host and not url.userinfo and url.path == "/" and url.scheme in {"https", "http"})
    host = url.host.lower().rstrip(".")
    try:
        loopback = ipaddress.ip_address(host).is_loopback
    except ValueError:
        loopback = host == "localhost"
    require(url.scheme == "https" or loopback)
    # Strip the trailing DNS dot and implicit default port when checking uniqueness.
    return str(url.copy_with(host=host)).rstrip("/")


def load_target(project_id):
    path = os.environ.get(CONFIG_ENV)
    if not path:
        return None
    with Path(path).open("rb") as stream:
        raw = stream.read(65537)
    require(len(raw) <= 65536)
    rows = json.loads(raw, object_pairs_hook=unique_object)
    require(isinstance(rows, dict))
    seen_origins, seen_ids = set(), set()
    chosen = None
    for project, row in rows.items():
        require(isinstance(project, str) and project and isinstance(row, dict))
        require(set(row) == {"base_url", "target_id", "token_env"})
        base_url, instance, token_env = origin(row["base_url"]), row["target_id"], row["token_env"]
        require(isinstance(instance, str) and SAFE_ID.fullmatch(instance))
        require(isinstance(token_env, str) and re.fullmatch(r"[A-Z_][A-Z0-9_]{0,127}", token_env))
        require(base_url not in seen_origins and instance not in seen_ids)
        seen_origins.add(base_url)
        seen_ids.add(instance)
        if project == project_id:
            token = os.getenv(token_env, "")
            require(token and token.isascii() and all(32 < ord(c) < 127 for c in token))
            require(instance != token and instance != os.getenv(SECRET_ENV))
            chosen = Target(base_url, digest(["auto-ontology-1", project, instance, base_url]), token, instance, tls_context())
    return chosen


class TargetRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    project_id: str = Field(min_length=1, max_length=256)


class PublishRequest(TargetRequest):
    target_id: str = Field(pattern=r"^[0-9a-f]{64}$")
    digest: str = Field(pattern=r"^[0-9a-f]{64}$")
    model: dict
    verify_only: bool


def authorized_target(project_id, supplied):
    secret = os.getenv(SECRET_ENV, "")
    if len(secret) < 32 or not secret.isascii():
        return None
    if not supplied or not supplied.isascii() or not hmac.compare_digest(secret, supplied):
        raise HTTPException(403, "服务身份验证失败")
    try:
        return load_target(project_id)
    except (ValueError, TypeError, OSError, httpx.InvalidURL, RecursionError):
        return None


@router.post("/target")
async def target_status(body: TargetRequest, secret: str | None = Header(None, alias="X-FinFlow-Ontology-Secret")):
    target = authorized_target(body.project_id, secret)
    if target is None:
        return {"configured": False, "target_id": None}
    return {"configured": True, "target_id": target.target_id, "target_label": target.label}


def exact(row, fields):
    require(isinstance(row, dict) and set(row) == set(fields.split()))


def rows(value):
    require(isinstance(value, list))
    return value


def strings(row, fields):
    require(all(isinstance(row[key], str) for key in fields.split()))


@dataclass
class Projection:
    name: str
    snapshot: str
    signature: str
    counts: dict
    terms: dict
    database_id: str


def project_model(document, project_id):
    """Canonicalize live IDs to named paths; preserve meaning and the order of columns and PKs."""
    model = deepcopy(document)
    exact(model, "data_layer semantic_layer zones")
    data, semantic = model["data_layer"], model["semantic_layer"]
    exact(data, "databases foreign_keys joins")
    exact(semantic, "terms semantic_fks sql_attributes custom_analyses")
    require(model["zones"] == data["joins"] == semantic["semantic_fks"] == semantic["custom_analyses"] == [])
    require(semantic["sql_attributes"] == {k: [] for k in ("manual", "sql", "table", "bridge_table")})
    require(len(rows(data["databases"])) == 1)
    db = data["databases"][0]
    exact(db, "id dialect schemas")
    require(db["dialect"] == "" and rows(db["schemas"]))
    name = db["schemas"][0]["database_name"]
    prefix = "finflow_" + digest(project_id)[:16] + "_"
    require(isinstance(name, str) and re.fullmatch(prefix + HASH, name))
    snapshot = name[len(prefix):]
    original_db_id = db["id"]
    counts = dict.fromkeys(COUNT_KEYS, 0)
    ids, paths = {}, set()
    table_ids, column_ids = {}, {}
    term_info = {}

    def identify(row, path, count_key):
        previous = row["id"]
        require(isinstance(previous, str) and SAFE_ID.fullmatch(previous) and previous not in ids)
        require(all(isinstance(part, str) and part for part in path))
        canonical = digest(path)
        require(canonical not in paths)
        paths.add(canonical)
        ids[previous] = row["id"] = canonical
        counts[count_key] += 1

    identify(db, ["database", name], "databases")
    for schema in db["schemas"]:
        exact(schema, "id name database_name tables")
        require(schema["database_name"] == name and schema["name"] in {"finflow_objects", "finflow_tables"})
        identify(schema, ["schema", schema["name"]], "schemas")
        require(rows(schema["tables"]))
        for table in schema["tables"]:
            exact(table, "id name description pk type columns")
            strings(table, "name description type")
            require(all(isinstance(key, str) for key in rows(table["pk"])))
            table_path = ["table", schema["name"], table["name"]]
            table_ids[table["id"]] = table_path
            identify(table, table_path, "tables")
            require(rows(table["columns"]))
            for col in table["columns"]:
                exact(col, "id name description type sample_values is_nullable is_unique")
                strings(col, "name description type")
                require(col["sample_values"] == [] and type(col["is_unique"]) is bool and type(col["is_nullable"]) is bool)
                column_ids[col["id"]] = table_path
                identify(col, ["column", *table_path[1:], col["name"]], "columns")
        schema["tables"].sort(key=lambda row: row["id"])
    db["schemas"].sort(key=lambda row: row["id"])
    for fk in rows(data["foreign_keys"]):
        exact(fk, "source_column_id target_column_id")
        for key, value in fk.items():
            require(value in column_ids)
            fk[key] = ids[value]
    data["foreign_keys"].sort(key=lambda fk: (fk["source_column_id"], fk["target_column_id"]))
    require(len({tuple(sorted(fk.items())) for fk in data["foreign_keys"]}) == len(data["foreign_keys"]))
    counts["foreign_keys"] = len(data["foreign_keys"])
    terms = rows(semantic["terms"])
    require(0 < len(terms) <= TERM_LIMIT)
    for term in terms:
        exact(term, "id name description represents columns_attributes")
        strings(term, "name description")
        require(re.search(r" \[ff:" + snapshot + ":" + HASH + r"\]$", term["name"]))
        require(len(rows(term["represents"])) == 1 and term["represents"][0] in table_ids)
        table_id = term["represents"][0]
        attrs = rows(term["columns_attributes"])
        term_info[term["name"]] = (term["id"], table_id, len(attrs), term["description"])
        identify(term, ["term", term["name"]], "terms")
        term["represents"] = [ids[table_id]]
        for attr in attrs:
            exact(attr, "id name description column_id")
            strings(attr, "name description")
            column_id = attr["column_id"]
            require(column_id in column_ids and column_ids[column_id] == table_ids[table_id])
            attr["column_id"] = ids[column_id]
            identify(attr, ["attribute", term["name"], attr["column_id"], attr["name"]], "column_attributes")
        attrs.sort(key=lambda row: row["id"])
    terms.sort(key=lambda row: row["id"])
    return Projection(name, snapshot, digest(model), counts, term_info, original_db_id)


class ModelLoader(getattr(yaml, "CSafeLoader", yaml.SafeLoader)):
    def construct_mapping(self, node, deep=False):
        return unique_object(self.construct_pairs(node, deep=deep))


def read_export(body):
    # Stop excessive nesting and alias expansion before constructing any objects.
    depth, nodes = 0, 0
    for event in yaml.parse(body, Loader=ModelLoader):
        if isinstance(event, yaml.CollectionStartEvent):
            depth += 1
        elif isinstance(event, yaml.CollectionEndEvent):
            depth -= 1
        if isinstance(event, yaml.NodeEvent):
            nodes += 1
        require(depth <= 32 and nodes <= 300000 and not isinstance(event, yaml.AliasEvent))
    return yaml.load(body, Loader=ModelLoader)

async def request(client, method, path, media="application/json", **kwargs):
    async with client.stream(method, path, **kwargs) as response:
        if response.status_code != 200:
            raise Rejected(response.status_code)
        require(response.headers.get("content-type", "").split(";")[0].strip().lower() == media)
        # Bound raw response bytes without allowing an intermediary to expand compressed bodies.
        require(response.headers.get("content-encoding", "identity").lower() == "identity")
        body = bytearray()
        async for chunk in response.aiter_bytes(65536):
            require(len(body) + len(chunk) <= RESPONSE_LIMIT)
            body.extend(chunk)
    if media == "application/json":
        return json.loads(body, object_pairs_hook=unique_object)
    return read_export(bytes(body))


def nonnegative(value):
    return type(value) is int and value >= 0


def count_map(entries, allowed):
    counts = {}
    for entry in rows(entries):
        term_id, count = entry["term_id"], entry["count"]
        require(term_id in allowed and term_id not in counts and nonnegative(count))
        counts[term_id] = count
    return counts


async def list_snapshot_terms(client, snapshot):
    # In 1.3, omitting limit returns the filtered terms BUT count arrays for the
    # entire glossary. Explicit pages keep counts local even on later snapshots.
    listed, attr_counts, sql_counts = {}, {}, {}
    total = None
    size = 100
    while total is None or len(listed) < total:
        listing = await request(client, "GET", "/api/terms", params={
            "query": "[ff:" + snapshot + ":", "skip": len(listed), "limit": size,
        })
        reported = listing["total"]
        require(nonnegative(reported) and reported <= TERM_LIMIT and (total is None or reported == total))
        total = reported
        terms = rows(listing["terms"])
        require(len(terms) == min(size, total - len(listed)))
        page = {}
        for term in terms:
            term_id = term["id"]
            require(isinstance(term_id, str) and SAFE_ID.fullmatch(term_id) and term_id not in listed and term_id not in page)
            page[term_id] = (term["name"], term["description"])
        attr_counts.update(count_map(listing["column_attribute_counts"], page))
        sql_counts.update(count_map(listing["sql_attribute_counts"], page))
        count_map(listing["related_counts"], page)
        listed.update(page)
    return listed, attr_counts, sql_counts


async def inspect_remote(client, expected, project_id):
    databases = await request(client, "GET", "/api/datasources/dbs")
    catalog = rows(databases["data"])
    require(nonnegative(databases["count"]) and databases["count"] == len(catalog))
    prefix = "finflow_" + digest(project_id)[:16] + "_"
    by_name, live_ids = {}, set()
    for entry in catalog:
        name, live_id = entry["name"], entry["id"]
        # The deployment is dedicated; refuse an aliased or previously assigned foreign catalog.
        require(isinstance(name, str) and re.fullmatch(prefix + HASH, name) and name not in by_name)
        require(isinstance(live_id, str) and SAFE_ID.fullmatch(live_id) and live_id not in live_ids)
        by_name[name] = live_id
        live_ids.add(live_id)
    database_id = by_name.get(expected.name)
    listed, attr_counts, sql_counts = await list_snapshot_terms(client, expected.snapshot)
    if database_id is None:
        require(not listed)
        return None
    exported = await request(client, "POST", "/api/model/export", "application/x-yaml",
                             json={"format": "auto_ontology", "databases": [database_id], "include_ontology": False})
    live = project_model(exported, project_id)
    require(live.database_id == database_id and live.signature == expected.signature)
    require(listed == {term_id: (name, desc) for name, (term_id, _, _, desc) in live.terms.items()})
    # The scoped exporter drops links to out-of-scope tables and attributes. The glossary
    # has global attribute counts; individual Term records expose all represented tables.
    async def check_term(name, entry):
        term_id, table_id, count, description = entry
        require(attr_counts.get(term_id, 0) == count and sql_counts.get(term_id, 0) == 0)
        detail = (await request(client, "GET", "/api/terms/" + term_id))["data"]
        require(detail["id"] == term_id and detail["name"] == name and detail["description"] == description)
        require(type(detail["table_count"]) is int and detail["table_count"] == 1 and len(rows(detail["tables"])) == 1)
        table = detail["tables"][0]
        require(table["id"] == table_id and table["db_id"] == database_id)
    entries = list(live.terms.items())
    for start in range(0, len(entries), 8):
        # Settle every read before releasing this request's connection pool.
        outcomes = await asyncio.gather(*(check_term(*item) for item in entries[start:start + 8]), return_exceptions=True)
        for outcome in outcomes:
            if isinstance(outcome, BaseException):
                raise outcome
    return database_id


def check_import(response, expected):
    require(response["success"] is True)
    summary = response["summary"]
    require(summary["format"] == "auto_ontology" and summary["replace"] is False)
    require("embeddings" not in summary and "pending_embed" not in summary)
    for key in ("terms", "column_attributes", "semantic_fks", "sql_attributes", "custom_analyses"):
        require(nonnegative(summary[key]) and summary[key] == expected.counts.get(key, 0))
    for key in COUNT_KEYS:
        created, skipped = summary["created"][key], summary["skipped"][key]
        require(nonnegative(created) and nonnegative(skipped) and created + skipped == expected.counts[key])
    ids = rows(summary["database_ids"])
    require(len(ids) == 1 and isinstance(ids[0], str) and SAFE_ID.fullmatch(ids[0]))
    return ids[0]


BAD_DATA = (ValueError, KeyError, TypeError, IndexError, RecursionError, yaml.YAMLError)


async def run_publish(body, target, client):
    if body.target_id != target.target_id:
        return {"status": "FAILED", "message": "项目发布目标已变更，请重新检查目标。"}
    try:
        require(digest(body.model) == body.digest)
        payload = model_bytes(body.model)
        require(len(payload) <= MODEL_LIMIT)
        expected = project_model(body.model, body.project_id)
    except BAD_DATA:
        return {"status": "FAILED", "message": "发布模型结构、项目范围或摘要无效。"}
    attempted = False
    waiting_for_import_response = False
    try:
        async with asyncio.timeout(75):
            live_id = await inspect_remote(client, expected, body.project_id)
            if not live_id:
                if body.verify_only:
                    return {"status": "ABSENT", "message": "目标目录未发现该快照。"}
                attempted = True
                waiting_for_import_response = True
                # UTF-8 JSON is a YAML document. The public route accepts multipart file.
                response = await request(client, "POST", "/api/model/import",
                                         params={"replace": "false", "embed": "false"},
                                         files={"file": ("finflow-model.yaml", payload, "application/x-yaml")})
                waiting_for_import_response = False
                assigned_id = check_import(response, expected)
                live_id = await inspect_remote(client, expected, body.project_id)
                require(live_id and assigned_id == live_id)
    except Rejected as error:
        # The 1.3 import transaction rolls back validation failures. Only its own
        # explicit rejection is definitive; a failed read-back cannot undo a write.
        if waiting_for_import_response and error.status in (401, 403, 422):
            return {"status": "FAILED", "message": "目标拒绝发布，请检查服务权限和模型版本。"}
        if error.status in (401, 403):
            return {"status": "UNCERTAIN", "message": "目标未授权读取核验，请管理员检查 API 令牌与权限后重新核对。"}
        return {"status": "UNCERTAIN", "message": "目标未完成读取核验，请重试以核对原快照。"}
    except (*BAD_DATA, httpx.HTTPError, TimeoutError):
        return {"status": "UNCERTAIN", "message": "无法确认目标与快照一致，请重试以核对原快照。"}
    return {"status": "VERIFIED", "receipt": {
        "provider": "nvidia_auto_ontology", "target_id": target.target_id, "digest": body.digest,
        "database_id": live_id, "verified": True, "counts": expected.counts,
        "mode": "imported" if attempted else "reconciled",
    }}


@router.post("/publish")
async def publish(body: PublishRequest, secret: str | None = Header(None, alias="X-FinFlow-Ontology-Secret")):
    target = authorized_target(body.project_id, secret)
    if target is None:
        return {"status": "FAILED", "message": "管理员尚未为该项目配置可用的独立发布目标。"}
    async with httpx.AsyncClient(base_url=target.base_url, headers={"x-api-key": target.token, "Accept-Encoding": "identity"},
                                 follow_redirects=False, trust_env=False, verify=target.tls, timeout=httpx.Timeout(15.0, connect=5.0)) as client:
        return await run_publish(body, target, client)
