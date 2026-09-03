import tempfile
import shutil
import subprocess
from pathlib import Path
from typing import Optional

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse, Response, StreamingResponse
from starlette.background import BackgroundTask

from ..config import settings
from ..deepseek import deepseek
from ..llm import llm
from ..models import (
    DatasetProfileRequest,
    DatasetProfileResponse,
    DeepSeekStatus,
    DocumentPreview,
    LlmStatus,
    ParsedDocument,
    DeliverableRequest,
    GenerateContentRequest,
    GenerateContentResponse,
    SpreadsheetProfile,
    SpreadsheetTransformRequest,
    SearchHit,
    SearchRequest,
    ResearchRequest,
    ResearchResponse,
    SummarizeRequest,
    SummarizeResponse,
    TransformGenerateRequest,
    TransformGenerateResponse,
)
from ..services import generate_content, generate_content_stream, profile_dataset, search, summarize
from ..document_parser import parse_document
from ..document_preview import preview_document
from ..office_preview import render_office_html
from ..deliverables import create_docx, create_excalidraw, create_financial_report, create_html_slides, create_mermaid, create_pdf, create_pptx
from ..ppt_skills import catalog as ppt_skill_catalog
from ..spreadsheet_files import profile_spreadsheet, transform_spreadsheet
from ..data_transform import generate_transform, profile_tabular, run_transform, sample_transform
from ..agent import AgentPlanRequest, AgentPlanResponse, OpenTaskRequest, load_skills, plan_with_agent, run_open_task_stream
import json


app = FastAPI(
    title="FinBTP Studio Worker",
    version="0.1.0",
    description="Document, knowledge Ref and file analysis worker",
)


@app.post("/v1/files/render-office-pdf")
async def render_office_preview(file: UploadFile = File(...), original_name: str = Form(...)) -> FileResponse:
    directory = Path(tempfile.mkdtemp(prefix="finflow-office-preview-"))
    try:
        source = directory / "upload"
        with source.open("wb") as output:
            while chunk := await file.read(1024 * 1024):
                output.write(chunk)
        preview = render_office_html(source, original_name, directory)
        return FileResponse(preview, media_type="text/html; charset=utf-8", filename="preview.html",
                            background=BackgroundTask(shutil.rmtree, directory, ignore_errors=True))
    except ValueError as exception:
        shutil.rmtree(directory, ignore_errors=True)
        raise HTTPException(status_code=422, detail=str(exception)) from exception
    except (RuntimeError, subprocess.SubprocessError) as exception:
        shutil.rmtree(directory, ignore_errors=True)
        raise HTTPException(status_code=503, detail=str(exception)) from exception


@app.get("/health")
async def health() -> dict:
    skills = load_skills(settings.agent_skills_dir)
    runtime_mode = "deep-agents" if settings.llm_provider.strip().lower() in {"codex", "deepseek"} else "model-gateway"
    return {
        "status": "online",
        "service": settings.service_name,
        "llmConfigured": llm.configured,
        "llmProvider": llm.provider,
        "llmModel": llm.model,
        "agentEnabled": settings.agent_enabled,
        "agentFramework": "deep-agents",
        "agentRuntimeMode": runtime_mode,
        "agentSkillCount": len(skills),
        "agentMcpConfigured": True,
        "agentGateway": "studio-mcp",
    }


@app.get("/v1/deepseek/status", response_model=DeepSeekStatus)
async def deepseek_status() -> DeepSeekStatus:
    return DeepSeekStatus(
        configured=deepseek.configured,
        model=settings.deepseek_chat_model,
        message=None if deepseek.configured else "API Key 尚未配置，当前使用本地处理",
    )


@app.get("/v1/llm/status", response_model=LlmStatus)
async def llm_status() -> LlmStatus:
    return LlmStatus(
        configured=llm.configured,
        provider=llm.provider,
        model=llm.model,
        message=None if llm.configured else "API Key 尚未配置，当前使用本地提取式分析",
    )


@app.get("/v1/agent/status")
async def agent_status() -> dict:
    skills = load_skills(settings.agent_skills_dir)
    runtime_mode = "deep-agents" if settings.llm_provider.strip().lower() in {"codex", "deepseek"} else "model-gateway"
    return {
        "enabled": settings.agent_enabled,
        "modelConfigured": llm.configured,
        "framework": "deep-agents",
        "runtimeMode": runtime_mode,
        "skills": [{"name": item.name, "description": item.description} for item in skills],
        "mcpConfigured": True,
        "gateway": "studio-mcp",
    }


@app.post("/v1/agent/plan", response_model=AgentPlanResponse)
async def plan_agent_task(request: AgentPlanRequest) -> AgentPlanResponse:
    try:
        result = await plan_with_agent(request)
    except RuntimeError as exception:
        raise HTTPException(status_code=503, detail=str(exception)) from exception
    if result is None:
        raise HTTPException(status_code=503, detail="Agent 模型尚未配置")
    return result


@app.post("/v1/agent/tasks/stream")
async def run_agent_task(request: OpenTaskRequest) -> StreamingResponse:
    async def stream():
        try:
            async for event in run_open_task_stream(request):
                yield json.dumps(event, ensure_ascii=False) + "\n"
        except Exception as exception:
            yield json.dumps({"type": "error", "message": str(exception), "progress": 100}, ensure_ascii=False) + "\n"

    return StreamingResponse(stream(), media_type="application/x-ndjson")


@app.post("/v1/knowledge/summarize", response_model=SummarizeResponse)
async def summarize_knowledge(request: SummarizeRequest) -> SummarizeResponse:
    return await summarize(request)


@app.post("/v1/knowledge/generate", response_model=GenerateContentResponse)
async def generate_knowledge_content(request: GenerateContentRequest) -> GenerateContentResponse:
    try:
        return await generate_content(request)
    except RuntimeError as exception:
        raise HTTPException(status_code=503, detail=str(exception)) from exception


@app.post("/v1/knowledge/generate/stream")
async def stream_knowledge_content(request: GenerateContentRequest) -> StreamingResponse:
    if not llm.configured:
        raise HTTPException(status_code=503, detail="大模型尚未配置，无法按生成要求制作成果")

    async def stream():
        async for event in generate_content_stream(request):
            yield json.dumps(event, ensure_ascii=False) + "\n"

    return StreamingResponse(stream(), media_type="application/x-ndjson")


@app.post("/v1/knowledge/search", response_model=list[SearchHit])
async def search_knowledge(request: SearchRequest) -> list[SearchHit]:
    return search(request)


@app.post("/v1/research/discover", response_model=ResearchResponse)
async def discover_research_sources(request: ResearchRequest) -> ResearchResponse:
    result = await llm.discover_sources(request.topic, request.max_sources)
    return ResearchResponse.model_validate(result)


@app.post("/v1/datasets/profile", response_model=DatasetProfileResponse)
async def dataset_profile(request: DatasetProfileRequest) -> DatasetProfileResponse:
    return profile_dataset(request)


@app.post("/v1/files/parse", response_model=ParsedDocument)
async def parse_file(
    file: UploadFile = File(...),
    original_name: str = Form(...),
) -> ParsedDocument:
    suffix = Path(original_name).suffix
    total = 0
    temp_path: Optional[Path] = None
    try:
        with tempfile.NamedTemporaryFile(prefix="finflow-", suffix=suffix, delete=False) as stream:
            temp_path = Path(stream.name)
            while chunk := await file.read(1024 * 1024):
                total += len(chunk)
                if total > settings.max_upload_bytes:
                    raise HTTPException(status_code=413, detail="文件超过允许的大小")
                stream.write(chunk)
        return parse_document(temp_path, original_name, file.content_type or "application/octet-stream")
    except HTTPException:
        raise
    except ValueError as exception:
        raise HTTPException(status_code=422, detail=str(exception)) from exception
    except Exception as exception:
        raise HTTPException(status_code=500, detail="文件解析失败") from exception
    finally:
        await file.close()
        if temp_path is not None:
            temp_path.unlink(missing_ok=True)


@app.post("/v1/files/preview", response_model=DocumentPreview)
async def preview_file(file: UploadFile = File(...), original_name: str = Form(...)) -> DocumentPreview:
    suffix = Path(original_name).suffix
    total = 0
    temp_path: Optional[Path] = None
    try:
        with tempfile.NamedTemporaryFile(prefix="finflow-preview-", suffix=suffix, delete=False) as stream:
            temp_path = Path(stream.name)
            while chunk := await file.read(1024 * 1024):
                total += len(chunk)
                if total > settings.max_upload_bytes:
                    raise HTTPException(status_code=413, detail="文件超过允许的大小")
                stream.write(chunk)
        return preview_document(temp_path, original_name)
    except HTTPException:
        raise
    except ValueError as exception:
        raise HTTPException(status_code=422, detail=str(exception)) from exception
    except Exception as exception:
        raise HTTPException(status_code=500, detail="文件预览生成失败") from exception
    finally:
        await file.close()
        if temp_path is not None:
            temp_path.unlink(missing_ok=True)


@app.post("/v1/spreadsheets/profile", response_model=SpreadsheetProfile)
async def spreadsheet_profile(file: UploadFile = File(...), original_name: str = Form(...)) -> SpreadsheetProfile:
    suffix = Path(original_name).suffix
    temp_path: Optional[Path] = None
    total = 0
    try:
        with tempfile.NamedTemporaryFile(prefix="finflow-sheet-", suffix=suffix, delete=False) as stream:
            temp_path = Path(stream.name)
            while chunk := await file.read(1024 * 1024):
                total += len(chunk)
                if total > settings.max_upload_bytes:
                    raise HTTPException(status_code=413, detail="文件超过允许的大小")
                stream.write(chunk)
        return profile_spreadsheet(temp_path, original_name)
    except HTTPException:
        raise
    except ValueError as exception:
        raise HTTPException(status_code=422, detail=str(exception)) from exception
    finally:
        await file.close()
        if temp_path is not None:
            temp_path.unlink(missing_ok=True)


@app.post("/v1/spreadsheets/transform")
async def spreadsheet_transform(file: UploadFile = File(...), original_name: str = Form(...),
                                operations: str = Form(...)) -> Response:
    suffix = Path(original_name).suffix
    temp_path: Optional[Path] = None
    total = 0
    try:
        request = SpreadsheetTransformRequest.model_validate(json.loads(operations))
        with tempfile.NamedTemporaryFile(prefix="finflow-transform-", suffix=suffix, delete=False) as stream:
            temp_path = Path(stream.name)
            while chunk := await file.read(1024 * 1024):
                total += len(chunk)
                if total > settings.max_upload_bytes:
                    raise HTTPException(status_code=413, detail="文件超过允许的大小")
                stream.write(chunk)
        content = transform_spreadsheet(temp_path, original_name, request)
        media_type = "application/vnd.ms-excel.sheet.macroEnabled.12" if suffix.lower() == ".xlsm" else (
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" if suffix.lower() == ".xlsx"
            else "text/tab-separated-values" if suffix.lower() == ".tsv" else "text/csv")
        return Response(content, media_type=media_type)
    except HTTPException:
        raise
    except (ValueError, json.JSONDecodeError) as exception:
        raise HTTPException(status_code=422, detail=str(exception)) from exception
    finally:
        await file.close()
        if temp_path is not None:
            temp_path.unlink(missing_ok=True)


async def _save_transform_files(files: list[UploadFile], metadata: list[dict]) -> tuple[Path, list[dict]]:
    directory = Path(tempfile.mkdtemp(prefix="finflow-transform-inputs-"))
    if len(files) != len(metadata):
        shutil.rmtree(directory, ignore_errors=True)
        raise HTTPException(status_code=422, detail="输入文件与配置数量不一致")
    resolved: list[dict] = []
    try:
        for index, (upload, item) in enumerate(zip(files, metadata)):
            name = str(item.get("name") or upload.filename or f"input-{index}.csv")
            target = directory / f"{index}{Path(name).suffix.lower()}"
            total = 0
            with target.open("wb") as stream:
                while chunk := await upload.read(1024 * 1024):
                    total += len(chunk)
                    if total > settings.max_transform_input_bytes:
                        raise HTTPException(status_code=413, detail=f"输入文件超过允许大小：{name}")
                    stream.write(chunk)
            resolved.append({**item, "name": name, "path": str(target)})
        return directory, resolved
    except Exception:
        shutil.rmtree(directory, ignore_errors=True)
        raise
    finally:
        for upload in files:
            await upload.close()


@app.post("/v1/data-transforms/profile")
async def data_transform_profile(file: UploadFile = File(...), original_name: str = Form(...),
                                 sheet_name: str = Form(default="")) -> dict:
    directory, inputs = await _save_transform_files(
        [file], [{"name": original_name, "alias": "input_data", "sheet_name": sheet_name or None}]
    )
    try:
        return profile_tabular(Path(inputs[0]["path"]), original_name, sheet_name or None)
    except ValueError as exception:
        raise HTTPException(status_code=422, detail=str(exception)) from exception
    finally:
        shutil.rmtree(directory, ignore_errors=True)


@app.post("/v1/data-transforms/generate", response_model=TransformGenerateResponse)
async def data_transform_generate(request: TransformGenerateRequest) -> TransformGenerateResponse:
    try:
        return await generate_transform(request)
    except ValueError as exception:
        raise HTTPException(status_code=422, detail=str(exception)) from exception
    except RuntimeError as exception:
        raise HTTPException(status_code=503, detail=str(exception)) from exception


@app.post("/v1/data-transforms/sample")
async def data_transform_sample(files: list[UploadFile] = File(...), metadata: str = Form(...),
                                script: str = Form(...)) -> dict:
    try:
        items = json.loads(metadata)
        directory, inputs = await _save_transform_files(files, items)
        try:
            return sample_transform(inputs, script)
        finally:
            shutil.rmtree(directory, ignore_errors=True)
    except (ValueError, json.JSONDecodeError) as exception:
        raise HTTPException(status_code=422, detail=str(exception)) from exception


@app.post("/v1/data-transforms/run")
async def data_transform_run(files: list[UploadFile] = File(...), metadata: str = Form(...),
                             script: str = Form(...)) -> FileResponse:
    directory: Optional[Path] = None
    try:
        items = json.loads(metadata)
        directory, inputs = await _save_transform_files(files, items)
        output = directory / "result.zip"
        run_transform(inputs, script, output)
        return FileResponse(output, media_type="application/zip", filename="result.zip",
                            background=BackgroundTask(shutil.rmtree, directory, ignore_errors=True))
    except (ValueError, json.JSONDecodeError) as exception:
        if directory is not None:
            shutil.rmtree(directory, ignore_errors=True)
        raise HTTPException(status_code=422, detail=str(exception)) from exception


@app.post("/v1/deliverables/pptx")
async def generate_pptx(request: DeliverableRequest) -> Response:
    return Response(create_pptx(request), media_type="application/vnd.openxmlformats-officedocument.presentationml.presentation")


@app.post("/v1/deliverables/html_slides")
async def generate_html_slides(request: DeliverableRequest) -> Response:
    return Response(create_html_slides(request), media_type="text/html; charset=utf-8")


@app.get("/v1/ppt-skills")
async def list_ppt_skills() -> list[dict[str, object]]:
    return ppt_skill_catalog()


@app.post("/v1/deliverables/docx")
async def generate_docx(request: DeliverableRequest) -> Response:
    return Response(create_docx(request), media_type="application/vnd.openxmlformats-officedocument.wordprocessingml.document")


@app.post("/v1/deliverables/pdf")
async def generate_pdf(request: DeliverableRequest) -> Response:
    return Response(create_pdf(request), media_type="application/pdf")


@app.post("/v1/deliverables/financial_report")
async def generate_financial_report(request: DeliverableRequest) -> Response:
    return Response(create_financial_report(request), media_type="application/json; charset=utf-8")


@app.post("/v1/deliverables/mermaid")
async def generate_mermaid(request: DeliverableRequest) -> Response:
    return Response(create_mermaid(request), media_type="text/plain; charset=utf-8")


@app.post("/v1/deliverables/excalidraw")
async def generate_excalidraw(request: DeliverableRequest) -> Response:
    return Response(create_excalidraw(request), media_type="application/json; charset=utf-8")
