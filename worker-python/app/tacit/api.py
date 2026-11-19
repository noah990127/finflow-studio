from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field

from ..runtime.interruptions import until_disconnected
from .engine import execute

router = APIRouter()
RESULT_ONLY_ACTIONS = {"preview_source", "read_section", "read_fields", "read_clauses", "search_context", "check_candidates"}


class Command(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    action: str = Field(min_length=1)
    actor: str = Field(min_length=1)
    state: dict
    catalog: list[dict]
    payload: dict
    result_only: bool = False


@router.post("/tacit/command")
async def apply_command(command: Command, request: Request):
    try:
        if command.result_only and command.action not in RESULT_ONLY_ACTIONS:
            raise ValueError("写入操作必须返回完整状态")
        response = await until_disconnected(execute(command.state, command.action, command.payload,
                                                    command.catalog, command.actor), request.is_disconnected)
        if command.result_only:
            return {"state": {}, "result": response["result"]}
        return response
    except ValueError as exc:
        raise HTTPException(422, str(exc)) from exc
    except RuntimeError as exc:
        raise HTTPException(503, str(exc)) from exc
