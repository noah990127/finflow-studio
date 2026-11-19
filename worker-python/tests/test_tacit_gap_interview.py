from copy import deepcopy

import pytest

from app.tacit.engine import execute


@pytest.mark.asyncio
async def test_gap_answer_tracks_supplements_repetition_and_specific_resolution():
    gap = {"gap_id": "limits", "question": "采购限额以什么为准？", "impact": "影响审批路径"}
    initial = await execute({}, "save_batch", {"section": "gaps", "items": [gap]}, [], "owner")
    state = initial["state"]

    async def answer(text, resolve=False):
        nonlocal state
        reply = await execute(state, "answer_gap", {"gap_id": "limits", "text": text, "resolve": resolve}, [], "owner")
        state = reply["state"]
        return reply["result"]

    with pytest.raises(ValueError, match="解决缺口"):
        await execute(deepcopy(state), "save_batch", {"section": "gaps", "items": [dict(gap, status="resolved")]},
                      [], "owner")
    first = await answer("限额按申请日口径，金额待核。")
    assert first["decision"] == "supplemented" and state["spec"]["gaps"][0].get("status", "open") == "open"
    for _ in range(2):
        assert (await answer("限额按申请日口径，金额待核。"))["decision"] == "no_increment"
    assert state["interviews"]["limits"]["paused"] and len(state["user_sources"]) == 1
    last = await answer("经核对，限额以申请日核准额度为准。", resolve=True)
    assert last["decision"] == "resolved" and not state["interviews"]["limits"]["paused"]
    finished = state["spec"]["gaps"][0]
    assert finished["status"] == "resolved" and finished["evidence"][-1]["quote"] == finished["resolution"]
    assert "unsupported_resolution" not in {i["code"] for i in state["issues"]}
    assert all(note["author"] == "owner" for note in state["user_sources"])
