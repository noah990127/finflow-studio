from pathlib import Path

from app.agent import load_skills


def test_loads_reusable_skills_from_markdown() -> None:
    skills = load_skills(str(Path(__file__).parents[1] / "skills"))

    assert {skill.name for skill in skills} == {
        "工作台通用协作",
        "结构化数据分析",
        "资料研究与溯源",
        "工作流编排",
    }
    assert all(skill.instructions for skill in skills)
