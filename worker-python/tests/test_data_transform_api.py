import io
import json
import zipfile

from fastapi.testclient import TestClient

from app.main import app


client = TestClient(app)


def _plan() -> dict:
    return {
        "version": "1.0",
        "goal": "关联订单和门店",
        "steps": [
            {"id": "result", "type": "join", "title": "补充门店名称",
             "left": "orders", "right": "stores", "join_type": "left",
             "keys": [{"left": "store_id", "right": "store_id"}],
             "expected_cardinality": "MANY_TO_ONE", "right_columns": ["store_name"]},
        ],
        "validations": [
            {"type": "unique", "input": "stores", "columns": ["store_id"], "severity": "error"},
        ],
        "output": {"format": "parquet", "partition_by": []},
    }


def _multipart() -> tuple[list[tuple[str, tuple[str, bytes, str]]], dict[str, str]]:
    files = [
        ("files", ("orders.csv", b"store_id,amount\nS1,10\nS2,20\n", "text/csv")),
        ("files", ("stores.csv", "store_id,store_name\nS1,深圳一店\nS2,深圳二店\n".encode(), "text/csv")),
    ]
    metadata = [
        {"alias": "orders", "name": "orders.csv", "source_kind": "FILE"},
        {"alias": "stores", "name": "stores.csv", "source_kind": "DATABASE_EXTRACT"},
    ]
    return files, {"metadata": json.dumps(metadata), "script": "", "plan": json.dumps(_plan())}


def test_samples_structured_plan_through_http_contract() -> None:
    files, data = _multipart()
    response = client.post("/v1/data-transforms/sample", files=files, data=data)
    assert response.status_code == 200
    body = response.json()
    assert body["stage"] == "PREVIEWED"
    assert body["stepReports"][0]["title"] == "补充门店名称"
    assert body["stepReports"][0]["join"]["matchRate"] == 1.0


def test_runs_structured_plan_and_returns_verified_parquet_package() -> None:
    files, data = _multipart()
    data["output_format"] = "parquet"
    response = client.post("/v1/data-transforms/run", files=files, data=data)
    assert response.status_code == 200
    with zipfile.ZipFile(io.BytesIO(response.content)) as archive:
        assert set(archive.namelist()) == {"result.parquet", "quality.json", "manifest.json"}
        quality = json.loads(archive.read("quality.json"))
        assert quality["stage"] == "VERIFIED"
        assert quality["rowCount"] == 2
