from pathlib import Path

from pydantic import BaseModel


class Skill(BaseModel):
    name: str
    description: str
    instructions: str


def load_skills(directory: str) -> list[Skill]:
    root = Path(directory)
    if not root.is_absolute():
        root = Path(__file__).resolve().parents[2] / root
    if not root.is_dir():
        return []
    result: list[Skill] = []
    for path in sorted(root.glob("**/SKILL.md")):
        text = path.read_text(encoding="utf-8")
        name = path.parent.name
        description = ""
        body = text
        if text.startswith("---"):
            parts = text.split("---", 2)
            if len(parts) == 3:
                metadata, body = parts[1], parts[2]
                for line in metadata.splitlines():
                    key, _, value = line.partition(":")
                    if key.strip() == "name" and value.strip():
                        name = value.strip().strip('"\'')
                    if key.strip() == "description" and value.strip():
                        description = value.strip().strip('"\'')
        if not description:
            description = next((line.lstrip("# ").strip() for line in body.splitlines() if line.strip()), name)
        result.append(Skill(name=name, description=description, instructions=body.strip()[:8000]))
    return result
