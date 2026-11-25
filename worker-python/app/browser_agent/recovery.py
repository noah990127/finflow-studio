"""Session isolation and safe pre-action recovery around native browser-use replay."""
import contextvars
import hashlib
import json
import re
from pathlib import Path
from urllib.parse import urlsplit

profile_directory = contextvars.ContextVar("browser_profile_directory", default=None)
register_focus = contextvars.ContextVar("browser_register_focus", default=None)
register_liveness = contextvars.ContextVar("browser_register_liveness", default=None)


class WindowDisconnected(RuntimeError):
    pass


def profile_path(root, owner, url, connection="default"):
    key = json.dumps([owner, urlsplit(url).hostname, connection])
    # SDK 0.13.14 copies Chrome profiles unless they use this managed-directory prefix.
    path = Path(root) / "profiles" / ("browser-use-user-data-dir-" + hashlib.sha256(key.encode()).hexdigest())
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    path.chmod(0o700)
    return path


def login_required(state):
    if not state:
        return False
    location = urlsplit(state.url)
    if re.search(r"(?:^|[/#._-])(login|signin|sign-in|passport|sso)(?:$|[/?.#_-])", (location.hostname or "") + location.path + "#" + location.fragment, re.I):
        return True
    elements = getattr(getattr(state, "dom_state", None), "selector_map", {}) or {}
    return any((getattr(e, "attributes", {}) or {}).get("type") == "password" for e in elements.values())


class RecoveryReplayMixin:
    """Split native batches into checkpoints. Only retry BEFORE any action dispatch."""
    async def _execute_history_step(self, history_item, delay, ai_step_llm=None, wait_for_elements=False):
        results = []
        for index, action in enumerate(history_item.model_output.action):
            item = history_item.model_copy(deep=True)
            item.model_output.action = [action.model_copy(deep=True)]
            element = history_item.state.interacted_element[index]
            item.state.interacted_element = [element]
            while True:
                state = await self.browser_session.get_browser_state_summary(include_screenshot=False)
                if login_required(state):
                    await self.recovery_human("登录已失效，任务已暂停，已完成的动作不会重跑。请在原窗口登录并返回业务页面，然后继续。")
                    continue
                if element is not None:
                    selectors = getattr(getattr(state, "dom_state", None), "selector_map", {}) or {}
                    matched = await self._update_action_indices(element, action.model_copy(deep=True), state) if selectors else None
                    if matched is None:
                        await self.recovery_human("当前页面尚未准备好，下一步还没有执行。请完成登录或返回原业务页面后继续；不要手动提交业务操作。")
                        continue
                break
            self.recovery_checkpoint("dispatching")
            # A dispatch error can mean the write succeeded. Never retry it automatically.
            try:
                batch = await super()._execute_history_step(item, delay, ai_step_llm, wait_for_elements)
                from .procedure import require_complete
                require_complete(batch, 1)
            except Exception:
                self.recovery_checkpoint("uncertain")
                raise RuntimeError("当前动作结果不明确，不能自动重发。请核对网站实际结果后再决定后续操作。") from None
            results.extend(batch)
            self.recovery_checkpoint("completed")
        return results
