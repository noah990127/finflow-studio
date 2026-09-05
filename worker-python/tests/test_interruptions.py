import asyncio

import pytest

from app.runtime.interruptions import ClientDisconnected, until_disconnected


@pytest.mark.asyncio
async def test_disconnect_cancels_model_work():
    started = asyncio.Event()
    stopped = asyncio.Event()

    async def work():
        started.set()
        try:
            await asyncio.Event().wait()
        finally:
            stopped.set()

    async def disconnected():
        return started.is_set()

    with pytest.raises(ClientDisconnected):
        await until_disconnected(work(), disconnected)
    assert stopped.is_set()


@pytest.mark.asyncio
async def test_connected_work_returns_unchanged():
    async def work():
        return {"summary": "完成"}

    async def disconnected():
        return False

    assert await until_disconnected(work(), disconnected) == {"summary": "完成"}


@pytest.mark.asyncio
async def test_server_cancellation_propagates_and_cleans_up():
    started = asyncio.Event()
    stopped = asyncio.Event()

    async def work():
        started.set()
        try:
            await asyncio.Event().wait()
        finally:
            stopped.set()

    async def disconnected():
        return False

    task = asyncio.create_task(until_disconnected(work(), disconnected))
    await started.wait()
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert stopped.is_set()
