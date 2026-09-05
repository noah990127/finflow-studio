import asyncio
from contextlib import suppress


class ClientDisconnected(Exception):
    """The gateway deliberately stopped waiting for this request."""


async def until_disconnected(work, is_disconnected):
    """Cancel model work when its calling gateway has stopped waiting."""
    task = asyncio.create_task(work)
    try:
        while not task.done():
            if await is_disconnected():
                raise ClientDisconnected()
            await asyncio.wait({task}, timeout=0.1)
        return await task
    finally:
        if not task.done():
            task.cancel()
        with suppress(asyncio.CancelledError):
            await task
