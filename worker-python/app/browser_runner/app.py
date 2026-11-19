import hmac
import os
import asyncio
from contextlib import asynccontextmanager, suppress

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from pydantic import BaseModel, Field

from ..runtime.interruptions import until_disconnected
from .engine import RunRequest, execute, inspect_site, sites
from .sessions import SessionRequest, browsers


def authorize(authorization: str = Header(default="")):
    token = os.environ.get("BROWSER_RUNNER_TOKEN", "")
    if len(token) < 32 or not hmac.compare_digest(authorization, "Bearer " + token):
        raise HTTPException(403, "Browser Runner authorization required")


@asynccontextmanager
async def lifespan(app):
    async def cleanup():
        while True:
            await asyncio.sleep(60)
            await browsers.reap()
    task = asyncio.create_task(cleanup())
    try:
        yield
    finally:
        task.cancel()
        with suppress(asyncio.CancelledError):
            await task
        await browsers.reap(all_sessions=True)


app = FastAPI(title="FinFlow Browser Runner", lifespan=lifespan)


@app.get("/health")
def health():
    return {"status": "ok"}


class InspectRequest(BaseModel):
    site: str = Field(pattern=r"^[a-zA-Z0-9_-]{1,64}$")


@app.post("/sites", dependencies=[Depends(authorize)])
def list_sites():
    return {"sites": [{"site": key, "url": value["url"], "mode": "interactive",
                       "fixedExport": bool(value.get("columns"))} for key, value in sites().items()]}


@app.post("/session/{operation}", dependencies=[Depends(authorize)])
async def session_operation(operation: str, body: SessionRequest, request: Request):
    if operation not in {"open", "observe", "act", "commit", "preview", "close", "login", "restart_login", "resume", "status"}:
        raise HTTPException(404, "Unknown browser operation")
    try:
        async with asyncio.timeout(60):
            return await until_disconnected(browsers.operate(operation, body), request.is_disconnected)
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from exc
    except Exception as exc:
        from playwright.async_api import TimeoutError as BrowserTimeout
        code = "BROWSER_TIMEOUT" if isinstance(exc, (TimeoutError, BrowserTimeout)) else "BROWSER_EXECUTION_FAILED"
        hint = "请检查网络或原窗口状态，不要新建会话重复登录" if operation in {"open", "observe", "login", "resume", "status"} else "请检查原页面；提交结果不明时必须先核验，不能重复提交"
        raise HTTPException(400, f"{code}：{operation} 未完成（{type(exc).__name__}）。{hint}") from exc


class ExploreRequest(InspectRequest):
    goal: str = Field(min_length=1, max_length=4000)
    parameters: dict[str, str] = Field(default_factory=dict, max_length=30)


@app.post("/explore", dependencies=[Depends(authorize)])
async def explore(body: ExploreRequest, request: Request):
    from .exploration import propose
    try:
        return await until_disconnected(propose(body.site, body.goal, body.parameters), request.is_disconnected)
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from exc
    except Exception as exc:
        raise HTTPException(400, "Browser-use探索不可用，请检查隔离服务依赖和模型配置；未发布脚本") from exc


@app.post("/inspect", dependencies=[Depends(authorize)])
async def inspect(body: InspectRequest, request: Request):
    try:
        return await until_disconnected(inspect_site(body.site), request.is_disconnected)
    except Exception as exc:
        if isinstance(exc, ValueError):
            raise HTTPException(400, str(exc)) from exc
        raise HTTPException(400, "页面检查失败：请确认站点可达、浏览器已安装及页面未变化") from exc


@app.post("/run", dependencies=[Depends(authorize)])
async def run(body: RunRequest, request: Request):
    try:
        return await until_disconnected(execute(body), request.is_disconnected)
    except Exception as exc:
        if isinstance(exc, ValueError):
            raise HTTPException(400, str(exc)) from exc
        raise HTTPException(400, "浏览器执行失败：元素不唯一、页面变化或等待超时；未报告成功，请检查工序") from exc
