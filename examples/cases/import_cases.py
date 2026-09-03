#!/usr/bin/env python3
"""Import the curated FinBTP Studio demo cases through the public HTTP API."""

from __future__ import annotations

import argparse
import hashlib
import json
import mimetypes
import os
import sys
import uuid
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parent
CASE_NAMES = ("nvidia", "global-tax-2026")


class Api:
    def __init__(self, base_url: str) -> None:
        self.base_url = base_url.rstrip("/")

    def json(self, method: str, path: str, body: Any | None = None) -> Any:
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers = {"Accept": "application/json"}
        if data is not None:
            headers["Content-Type"] = "application/json; charset=utf-8"
        request = Request(self.base_url + path, data=data, headers=headers, method=method)
        try:
            with urlopen(request, timeout=120) as response:
                payload = response.read()
                return json.loads(payload) if payload else None
        except HTTPError as error:
            detail = error.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"{method} {path} failed: HTTP {error.code} {detail}") from error
        except URLError as error:
            raise RuntimeError(f"Cannot reach FinBTP Studio API at {self.base_url}: {error.reason}") from error

    def multipart(self, endpoint: str, fields: dict[str, str], file_name: str,
                  media_type: str, content: bytes) -> dict[str, Any]:
        boundary = "----finflow-" + uuid.uuid4().hex
        parts: list[bytes] = []
        for name, value in fields.items():
            parts.append((
                f"--{boundary}\r\n"
                f'Content-Disposition: form-data; name="{name}"\r\n'
                "Content-Type: text/plain; charset=utf-8\r\n\r\n"
                f"{value}\r\n"
            ).encode("utf-8"))
        parts.append((
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="file"; filename="{file_name}"\r\n'
            f"Content-Type: {media_type}\r\n\r\n"
        ).encode("utf-8"))
        parts.extend((content, f"\r\n--{boundary}--\r\n".encode("ascii")))
        request = Request(
            self.base_url + endpoint,
            data=b"".join(parts),
            headers={"Content-Type": f"multipart/form-data; boundary={boundary}", "Accept": "application/json"},
            method="POST",
        )
        try:
            with urlopen(request, timeout=300) as response:
                return json.loads(response.read())
        except HTTPError as error:
            detail = error.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"Upload {file_name} failed: HTTP {error.code} {detail}") from error
        except URLError as error:
            raise RuntimeError(f"Cannot reach FinBTP Studio API at {self.base_url}: {error.reason}") from error

    def upload(self, project_id: str, path: Path) -> dict[str, Any]:
        media_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
        return self.multipart(f"/api/projects/{project_id}/files", {}, path.name, media_type, path.read_bytes())

    def upload_deliverable(self, project_id: str, title: str, format_name: str, file_name: str,
                           content: bytes, source_spec: dict[str, Any], resource_id: str | None) -> dict[str, Any]:
        media_type = mimetypes.guess_type(file_name)[0] or "application/octet-stream"
        fields = {
            "title": title,
            "format": format_name,
            "sourceSpec": json.dumps(source_spec, ensure_ascii=False, separators=(",", ":")),
        }
        if resource_id:
            fields["resourceId"] = resource_id
        return self.multipart(f"/api/projects/{project_id}/deliverables/import", fields,
                              file_name, media_type, content)


def load_manifest(case_name: str) -> tuple[Path, dict[str, Any]]:
    case_dir = ROOT / case_name
    manifest = json.loads((case_dir / "manifest.json").read_text(encoding="utf-8"))
    return case_dir, manifest


def resolve_value(item: dict[str, Any], name: str) -> str:
    env_name = item.get(name + "Env")
    if env_name and os.getenv(env_name):
        return os.environ[env_name]
    return str(item.get(name + "Default", item.get(name, "")))


def replace_refs(value: Any, replacements: dict[str, str]) -> Any:
    if isinstance(value, str):
        if value in replacements:
            return replacements[value]
        for old_id, new_id in replacements.items():
            if value.startswith(old_id + ":v"):
                return new_id + value[len(old_id):]
        return value
    if isinstance(value, list):
        return [replace_refs(item, replacements) for item in value]
    if isinstance(value, dict):
        return {key: replace_refs(item, replacements) for key, item in value.items()}
    return value


def validate_case(case_name: str) -> None:
    case_dir, manifest = load_manifest(case_name)
    required = ("name", "description", "files", "connections", "workflow", "legacyRefs")
    missing = [key for key in required if key not in manifest]
    if missing:
        raise RuntimeError(f"{case_name}: manifest missing {', '.join(missing)}")
    paths = [case_dir / manifest["workflow"]]
    paths.extend(case_dir / item["path"] for item in manifest["files"])
    paths.extend(case_dir / item["path"] for item in manifest.get("artifacts", []))
    paths.extend(case_dir / item["sourceSpec"] for item in manifest.get("artifacts", []) if item.get("sourceSpec"))
    if manifest.get("postgresInit"):
        paths.append(case_dir / manifest["postgresInit"])
    absent = [str(path.relative_to(ROOT)) for path in paths if not path.is_file()]
    if absent:
        raise RuntimeError(f"{case_name}: missing files: {', '.join(absent)}")
    workflow_text = (case_dir / manifest["workflow"]).read_text(encoding="utf-8")
    json.loads(workflow_text)
    file_keys = {item["key"] for item in manifest["files"]}
    connection_keys = {item["key"] for item in manifest["connections"]}
    for old_id, reference in manifest["legacyRefs"].items():
        kind, separator, key = reference.partition(":")
        if not separator or kind not in {"file", "connection"}:
            raise RuntimeError(f"{case_name}: invalid legacy reference {reference}")
        valid_keys = file_keys if kind == "file" else connection_keys
        if key not in valid_keys or old_id not in workflow_text:
            raise RuntimeError(f"{case_name}: unresolved legacy reference {old_id} -> {reference}")
    for old_id, reference in manifest.get("snapshotRefs", {}).items():
        kind, separator, key = reference.partition(":")
        if not separator or kind != "file" or key not in file_keys or not old_id:
            raise RuntimeError(f"{case_name}: invalid snapshot reference {old_id} -> {reference}")
    valid_formats = {"pptx", "html_slides", "docx", "pdf", "mermaid", "excalidraw", "financial_report"}
    for artifact in manifest.get("artifacts", []):
        if not artifact.get("title") or artifact.get("format") not in valid_formats:
            raise RuntimeError(f"{case_name}: invalid artifact entry {artifact}")


def ensure_project(api: Api, manifest: dict[str, Any]) -> dict[str, Any]:
    projects = api.json("GET", "/api/projects")
    existing = next((item for item in projects if item["name"] == manifest["name"]), None)
    if existing:
        print(f"  project exists: {existing['name']}")
        return existing
    project = api.json("POST", "/api/projects", {
        "name": manifest["name"], "description": manifest["description"]
    })
    print(f"  project created: {project['name']}")
    return project


def ensure_files(api: Api, project_id: str, case_dir: Path, manifest: dict[str, Any]) -> dict[str, str]:
    existing = {item["name"]: item for item in api.json("GET", f"/api/projects/{project_id}/files")}
    result: dict[str, str] = {}
    for item in manifest["files"]:
        path = case_dir / item["path"]
        resource = existing.get(path.name)
        if resource is None:
            resource = api.upload(project_id, path)
            print(f"  file uploaded: {path.name}")
        else:
            print(f"  file exists: {path.name}")
        result[item["key"]] = resource["id"]
    return result


def ensure_connections(api: Api, project_id: str, manifest: dict[str, Any]) -> dict[str, str]:
    existing = {item["name"]: item for item in api.json("GET", f"/api/projects/{project_id}/data-connections")}
    result: dict[str, str] = {}
    for item in manifest["connections"]:
        connection = existing.get(item["name"])
        body = {
            "name": item["name"],
            "sourceType": item["sourceType"],
            "jdbcUrl": resolve_value(item, "jdbcUrl"),
            "username": resolve_value(item, "username"),
            "secretRef": resolve_value(item, "secretRef"),
            "options": item.get("options", {}),
        }
        if connection is None:
            connection = api.json("POST", "/api/data-connections", {"projectId": project_id, **body})
            print(f"  connection created: {connection['name']}")
        else:
            connection = api.json("PUT", f"/api/data-connections/{connection['id']}", body)
            print(f"  connection updated: {connection['name']}")
        result[item["key"]] = connection["id"]
    return result


def install_workflow(
    api: Api,
    project_id: str,
    case_dir: Path,
    manifest: dict[str, Any],
    files: dict[str, str],
    connections: dict[str, str],
) -> None:
    replacements: dict[str, str] = {}
    for old_id, reference in manifest["legacyRefs"].items():
        kind, key = reference.split(":", 1)
        replacements[old_id] = files[key] if kind == "file" else connections[key]
    workflow = json.loads((case_dir / manifest["workflow"]).read_text(encoding="utf-8"))
    workflow = replace_refs(workflow, replacements)
    current = api.json("GET", f"/api/projects/{project_id}/workflow")
    workflow["expectedVersion"] = current["currentVersion"]
    api.json("PUT", f"/api/projects/{project_id}/workflow", workflow)
    print(f"  workflow installed: {workflow['name']}")


def ensure_deliverables(api: Api, project_id: str, manifest: dict[str, Any]) -> None:
    existing = {item["name"] for item in api.json("GET", f"/api/projects/{project_id}/deliverables")}
    for item in manifest.get("deliverables", []):
        if item["title"] in existing:
            print(f"  deliverable exists: {item['title']}")
            continue
        body = dict(item)
        body["projectId"] = project_id
        body["resourceId"] = None
        api.json("POST", "/api/deliverables", body)
        print(f"  deliverable created: {item['title']}")


def ensure_artifacts(api: Api, project_id: str, case_dir: Path, manifest: dict[str, Any],
                     files: dict[str, str]) -> None:
    existing = {item["name"]: item for item in api.json("GET", f"/api/projects/{project_id}/deliverables")}
    replacements = {
        old_id: files[reference.split(":", 1)[1]]
        for old_id, reference in manifest.get("snapshotRefs", {}).items()
    }
    for item in manifest.get("artifacts", []):
        path = case_dir / item["path"]
        content = path.read_bytes()
        if item["format"] in {"financial_report", "html_slides", "mermaid", "excalidraw"}:
            for old_id, new_id in replacements.items():
                content = content.replace(old_id.encode("utf-8"), new_id.encode("utf-8"))
        source_spec: dict[str, Any] = {}
        if item.get("sourceSpec"):
            source_spec = replace_refs(
                json.loads((case_dir / item["sourceSpec"]).read_text(encoding="utf-8")), replacements
            )
        checksum = "sha256:" + hashlib.sha256(content).hexdigest()
        current = existing.get(item["title"])
        if current and current.get("checksum") == checksum:
            print(f"  artifact exists: {item['title']}")
            continue
        imported = api.upload_deliverable(project_id, item["title"], item["format"],
                                          path.name, content, source_spec,
                                          current["id"] if current else None)
        existing[item["title"]] = imported
        action = "updated" if current else "imported"
        print(f"  artifact {action}: {item['title']}")


def import_case(api: Api, case_name: str) -> None:
    case_dir, manifest = load_manifest(case_name)
    print(f"Importing {manifest['name']}")
    project = ensure_project(api, manifest)
    files = ensure_files(api, project["id"], case_dir, manifest)
    connections = ensure_connections(api, project["id"], manifest)
    install_workflow(api, project["id"], case_dir, manifest, files, connections)
    ensure_deliverables(api, project["id"], manifest)
    ensure_artifacts(api, project["id"], case_dir, manifest, files)


def main() -> int:
    parser = argparse.ArgumentParser(description="Import curated FinBTP Studio demo cases")
    parser.add_argument("--api", default=os.getenv("FINFLOW_API_BASE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--case", choices=CASE_NAMES, action="append", dest="cases")
    parser.add_argument("--check", action="store_true", help="validate resources without writing to FinBTP Studio")
    args = parser.parse_args()
    selected = tuple(args.cases or CASE_NAMES)
    try:
        for case_name in selected:
            validate_case(case_name)
        if args.check:
            print("Case resources are valid: " + ", ".join(selected))
            return 0
        api = Api(args.api)
        for case_name in selected:
            import_case(api, case_name)
        print("Demo case import completed")
        return 0
    except (OSError, ValueError, RuntimeError, json.JSONDecodeError) as error:
        print(f"Import failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
