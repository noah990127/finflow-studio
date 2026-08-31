import zipfile
from pathlib import Path

import pytest

from app.data_transform import run_transform, sample_transform, validate_transform_sql


def write_csv(path: Path, content: str) -> None:
    path.write_text(content, encoding="utf-8")


def test_joins_multiple_structured_inputs_and_writes_quality_report(tmp_path: Path) -> None:
    sales = tmp_path / "sales.csv"
    customers = tmp_path / "customers.csv"
    write_csv(sales, "customer_id,amount\nC001,1200.5\nC003,450.25\n")
    write_csv(customers, "customer_id,name\nC001,华东一部\nC002,华南二部\n")
    inputs = [
        {"alias": "sales", "name": "sales.csv", "path": str(sales)},
        {"alias": "customers", "name": "customers.csv", "path": str(customers)},
    ]
    script = """
        SELECT s.customer_id, c.name, s.amount
        FROM sales s LEFT JOIN customers c ON s.customer_id = c.customer_id
    """

    sample = sample_transform(inputs, script)
    assert sample["sampleRowCount"] == 2
    assert sample["rows"][1]["name"] is None

    output = tmp_path / "result.zip"
    report = run_transform(inputs, script, output)
    assert report["rowCount"] == 2
    assert report["columnCount"] == 3
    with zipfile.ZipFile(output) as archive:
        assert set(archive.namelist()) == {"result.csv", "quality.json"}


@pytest.mark.parametrize("script", [
    "DROP TABLE sales",
    "SELECT * FROM read_csv_auto('/tmp/private.csv')",
    "SELECT * FROM unknown_data",
    "SELECT * FROM sales; SELECT * FROM sales",
])
def test_rejects_unsafe_or_unconnected_sql(script: str) -> None:
    with pytest.raises(ValueError):
        validate_transform_sql(script, ["sales"])
