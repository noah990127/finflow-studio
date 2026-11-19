import asyncio
import json
import pytest
from app.agent.activity import ActivityWatch


@pytest.mark.asyncio
async def test_idle_ignores_keepalive_but_real_progress_resets(tmp_path):
    watch = ActivityWatch(tmp_path, 10, 60)
    watch.last -= 11
    with pytest.raises(RuntimeError, match='未返回有效进展'):
        watch.check()
    watch.progress()
    watch.check()


@pytest.mark.asyncio
async def test_tool_uses_separate_deadline_and_finishing_resets_idle(tmp_path):
    watch = ActivityWatch(tmp_path, 10, 60)
    watch.path.write_text(json.dumps({'state': 'running'}))
    watch.check()
    watch.last -= 20
    watch.check()
    watch.tool_started -= 61
    with pytest.raises(RuntimeError, match='工具调用超过'):
        watch.check()
    watch.path.write_text(json.dumps({'state': 'finished'}))
    watch.check()
    assert not watch.active
