import copy
from types import SimpleNamespace as NS
import pytest
from app.browser_agent.recovery import profile_path, login_required, RecoveryReplayMixin


class Model(NS):
    def model_copy(self, deep=False):
        return copy.deepcopy(self)


def state(url="https://example.com/work", password=False):
    return NS(url=url, dom_state=NS(selector_map={1: NS(attributes={"type": "password" if password else "text"})}))


def test_profile_is_private_and_isolated(tmp_path):
    a = profile_path(tmp_path, "alice", "https://example.com/work?sid=secret")
    assert a == profile_path(tmp_path, "alice", "https://example.com/other")
    assert a != profile_path(tmp_path, "bob", "https://example.com/work")
    assert a != profile_path(tmp_path, "alice", "https://other.com/work")
    assert a != profile_path(tmp_path, "alice", "https://example.com/work", "second-account")
    assert a.stat().st_mode & 0o777 == 0o700
    assert "secret" not in str(a)


def test_login_detection_is_conservative():
    assert login_required(state("https://sso.example.com/login"))
    assert login_required(state(password=True))
    assert not login_required(state("about:blank"))
    assert not login_required(state())


class NativeExecutor:
    async def _execute_history_step(self, item, *args):
        self.calls.append(item.model_output.action[0].number)
        if self.fail:
            raise RuntimeError("connection lost after submission")
        self.ready = False
        return [NS(error=None)]


class Replay(RecoveryReplayMixin, NativeExecutor):
    def __init__(self):
        self.calls, self.prompts, self.checkpoints = [], [], []
        self.ready, self.fail = False, False
        self.browser_session = NS(get_browser_state_summary=self.snapshot)

    async def snapshot(self, **kwargs):
        return state() if self.ready else state("https://example.com/login")

    async def _update_action_indices(self, element, action, current):
        return action if self.ready else None

    async def recovery_human(self, message):
        self.prompts.append(message)
        # First click on Continue while still logged out must not execute anything.
        self.ready = len(self.prompts) > 1

    def recovery_checkpoint(self, status):
        self.checkpoints.append(status)


def item():
    return Model(model_output=Model(action=[Model(number=1), Model(number=2)]),
                 state=Model(interacted_element=[Model(), Model()]))


@pytest.mark.asyncio
async def test_relogin_keeps_same_run_and_does_not_repeat_completed_actions():
    replay = Replay()
    results = await replay._execute_history_step(item(), 0)
    assert len(results) == 2
    assert replay.calls == [1, 2]
    assert len(replay.prompts) == 3
    assert replay.checkpoints == ["dispatching", "completed", "dispatching", "completed"]


@pytest.mark.asyncio
async def test_uncertain_submission_is_never_retried():
    replay = Replay()
    replay.ready = replay.fail = True
    with pytest.raises(RuntimeError, match="不能自动重发"):
        await replay._execute_history_step(item(), 0)
    assert replay.calls == [1]
    assert replay.checkpoints == ["dispatching", "uncertain"]
