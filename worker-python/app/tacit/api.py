from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field

from ..runtime.interruptions import until_disconnected
from .engine import execute

router = APIRouter()


class Command(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    action: str = Field(min_length=1)
    actor: str = Field(min_length=1)
    state: dict
    catalog: list[dict]
    payload: dict


@router.post("/tacit/command")
async def apply_command(command: Command, request: Request):
    try:
        return await until_disconnected(execute(command.state, command.action, command.payload,
                                                command.catalog, command.actor), request.is_disconnected)
    except ValueError as exc:
        raise HTTPException(422, str(exc)) from exc
    except RuntimeError as exc:
        raise HTTPException(503, str(exc)) from exc
