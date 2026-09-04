from io import BytesIO

import pytest
from fastapi import HTTPException, UploadFile

from app.api.uploads import temporary_upload


@pytest.mark.asyncio
async def test_temporary_upload_is_removed_and_closed() -> None:
    upload = UploadFile(filename="notes.txt", file=BytesIO(b"verified content"))

    async with temporary_upload(
        upload, "notes.txt", prefix="finflow-test-", max_bytes=1024
    ) as path:
        assert path.read_bytes() == b"verified content"
        assert path.exists()

    assert not path.exists()
    assert upload.file.closed


@pytest.mark.asyncio
async def test_temporary_upload_rejects_oversized_file_and_cleans_up() -> None:
    upload = UploadFile(filename="large.csv", file=BytesIO(b"12345"))

    with pytest.raises(HTTPException) as error:
        async with temporary_upload(
            upload, "large.csv", prefix="finflow-test-", max_bytes=4
        ):
            raise AssertionError("oversized upload must not be yielded")

    assert error.value.status_code == 413
    assert upload.file.closed
