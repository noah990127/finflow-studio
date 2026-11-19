"""Regenerate a separate APEC sample from saved research; never overwrite it.

Run with the worker virtualenv and FINFLOW_TEST_PASSWORD in the environment.
The sample reuses the prior report's conclusions, not a fresh investigation.
"""
import asyncio
import hashlib
import json
import os
import sys
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "worker-python"))

from app.artifacts.service import generate_artifact
from app.artifacts.presentation_review import render_pages
from app.models import DeliverableRequest, DeliverableRef


async def main():
    output = ROOT / "artifacts" / "apec-presentation-v2"
    output.mkdir(parents=True, exist_ok=True)
    client = httpx.Client(base_url="http://127.0.0.1:8080", timeout=120)
    client.post("/api/auth/login", json={"username": "default", "password": os.environ["FINFLOW_TEST_PASSWORD"]}).raise_for_status()
    original_id = "1dcf7c08-53b4-41ab-b26b-0d01e5f460a4"
    original = client.get(f"/api/deliverables/{original_id}"); original.raise_for_status()
    sources = client.get(f"/api/deliverables/{original_id}/citations"); sources.raise_for_status()
    refs = [DeliverableRef.model_validate(source) for source in sources.json()]
    pages = [
        ("承办打开合作窗口，收益仍待落实", "核心判断", "statement",
         "中国承办带来合作机会，但不能把会议预期等同于已实现收益。",
         ["原调研记录：中国于2026年第三次承办APEC。",
          "潜在影响涉及贸易投资、创新合作与市场预期。",
          "最终成果与中国专项数据仍需补证。"], [0, 1]),
        ("议程动态与最终成果需要分开判断", "证据边界", "comparison",
         "官网议程动态只能证明活动进展，不能替代最终宣言与落地结果。",
         ["已记录：领导人周与相关议程动态。",
          "待核实：准确日程、参会名单与最终文件。",
          "待追踪：项目承诺是否形成实际执行安排。"], [0]),
        ("区域统计不能直接推导中国收益", "数据口径", "decision",
         "不同年份、区域与统计口径的数据，不宜放在一起直接比较。",
         ["3.4%预测：原调研未核实年份与口径，不作为中国增速使用。",
          "46.1%占比：原报告记载为2024年全球货物与商业服务出口口径。",
          "评估中国影响前，补齐中国专项数据并保留原始来源。"], [0]),
        ("合作共识不会自动改变经营规则", "作用机制", "statement",
         "APEC依赖自愿合作与协商一致，实际影响取决于各经济体落实。",
         ["合作决定不具法律约束力，不能等同于强制政策。",
          "会议共识不会自动改变关税、签证或企业准入。",
          "应逐层验证政策方向、合作项目与国内落实。"], [1]),
        ("把影响评估拆成三个观察阶段", "影响研判", "timeline",
         "短期看预期，中期看项目，长期看执行效果。",
         ["短期：观察合作预期变化，不提前确认经济收益。",
          "中期：追踪项目推进与规则协调是否发生。",
          "长期：依据落实情况和生产率变化验证判断。"], [1]),
        ("先补证，再更新中国影响判断", "下一步", "process",
         "下一轮更新应补齐证据，而不是重复放大会议预期。",
         ["核验最终文件：明确通过内容与适用范围。",
          "补齐中国数据：统一时间、区域与统计口径。",
          "追踪实际落实：将项目进展与经营结果对应。"], [0, 1]),
    ]
    request = DeliverableRequest(title="APEC进展与中国影响",
        subtitle="基于2026年11月16日已有调研的版式验证稿 · 未重新核验外部事实",
        plan_mode="fixed", ppt_skill="studio-professional-native", sections=[{
            "heading": title, "chapter": chapter, "layout": layout, "core_message": core,
            "bullets": bullets, "refs": [refs[i] for i in indexes],
        } for title, chapter, layout, core, bullets, indexes in pages])
    result = await generate_artifact("pptx", request)
    destination = output / "APEC-research-v2.pptx"
    destination.write_bytes(result.content)
    (output / "content.json").write_text(request.model_dump_json(indent=2), encoding="utf-8")
    (output / "quality.json").write_text(result.quality.model_dump_json(indent=2), encoding="utf-8")
    pdf, images = render_pages(result.content, output)
    # Save a lightweight local contact sheet for inspection, not as the editable deck.
    from PIL import Image, ImageDraw
    sheet = Image.new("RGB", (1440, ((len(images) + 1) // 2) * 430), "#e6e8eb")
    for index, path in enumerate(images):
        with Image.open(path) as page:
            page.thumbnail((696, 392))
            left, top = (index % 2) * 720 + 12, (index // 2) * 430 + 12
            sheet.paste(page, (left, top))
            ImageDraw.Draw(sheet).text((left, top + 397), f"{index + 1:02}", fill="#30343a")
    sheet.save(output / "overview.png")
    # Import as a new resource; no resourceId means the existing PPT is untouched.
    sample = json.loads((output / "resource.json").read_text()) if (output / "resource.json").exists() else None
    response = client.post(f"/api/projects/{original.json()['projectId']}/deliverables/import",
        data={**({"resourceId": sample["id"]} if sample else {}),
              "title": "APEC进展与中国影响 · 新版效果验证", "format": "pptx",
              "sourceSpec": json.dumps({**request.model_dump(), "quality_report": result.quality.model_dump(),
                  "derived_from": original_id}, ensure_ascii=False)},
        files={"file": (destination.name, result.content, "application/vnd.openxmlformats-officedocument.presentationml.presentation")})
    response.raise_for_status()
    (output / "resource.json").write_text(response.text, encoding="utf-8")
    current = client.get(f"/api/deliverables/{original_id}"); current.raise_for_status()
    assert current.json()["checksum"] == original.json()["checksum"], "Original changed"
    print(json.dumps({"resource": response.json(), "quality": result.quality.model_dump(),
                      "output": str(destination), "pdf": str(pdf), "pages": len(images)}, ensure_ascii=False))


if __name__ == "__main__":
    asyncio.run(main())
