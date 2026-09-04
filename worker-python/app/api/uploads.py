from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from pathlib import Path
import tempfile

from fastapi import HTTPException, UploadFile


@asynccontextmanager
async def temporary_upload(
    upload: UploadFile,
    original_name: str,
    *,
    prefix: str,
    max_bytes: int,
) -> AsyncIterator[Path]:
    """Persist one upload for a bounded operation and always release its resources."""
    temp_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            prefix=prefix,
            suffix=Path(original_name).suffix,
            delete=False,
        ) as stream:
            temp_path = Path(stream.name)
            total = 0
            while chunk := await upload.read(1024 * 1024):
                total += len(chunk)
                if total > max_bytes:
                    raise HTTPException(status_code=413, detail="文件超过允许的大小")
                stream.write(chunk)
        yield temp_path
    finally:
        await upload.close()
        if temp_path is not None:
            temp_path.unlink(missing_ok=True)
