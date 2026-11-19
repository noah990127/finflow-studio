"""Vendor a reviewed subset of Presenton's Executive template for offline use.

Usage: python scripts/import-presenton-template.py template.json LICENSE
No remote fetching at runtime. Keep upstream data and license unchanged.
"""
import hashlib
import json
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "worker-python/app/templates/presenton-executive-v1"
LAYOUT_IDS = [
    "centered_title_metadata_6418", "large_title_list_footer_2424",
    "title_with_stacked_cards_2967", "title_timeline_sections_footer_6488",
    "title_cards_chart_footer_2861",
]


def main():
    source, license_path = map(Path, sys.argv[1:])
    payload = json.loads(source.read_text())
    layouts = {item["id"]: item for item in payload["layouts"]}
    if not all(key in layouts for key in LAYOUT_IDS):
        raise ValueError("Upstream template changed; review mappings before importing")
    TARGET.mkdir(parents=True, exist_ok=True)
    (TARGET / "layouts.json").write_text(json.dumps(
        {key: layouts[key] for key in LAYOUT_IDS}, ensure_ascii=False, indent=2) + "\n")
    shutil.copyfile(license_path, TARGET / "LICENSE")
    (TARGET / "provenance.json").write_text(json.dumps({
        "source": "https://github.com/presenton/presenton/tree/main/templates/executive",
        "upstream_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
        "license": "Apache-2.0", "adapter_version": 1,
        "modifications": "Selected layouts; native text/chart export, CJK typography, no remote images, no placeholder copy."
    }, indent=2) + "\n")


if __name__ == "__main__":
    main()
