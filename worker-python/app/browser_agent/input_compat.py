"""Version-checked browser-use input compatibility, shared by agent and replay."""
import ast
import inspect
import textwrap
from contextvars import ContextVar
from importlib.metadata import version

from .interaction import BrowserTaskFailure

_checks = ContextVar("browser_input_checks", default=None)
_installed = False
_BLUR = "{ type: 'blur', bubbles: true, cancelable: true }"

# Keep values in the page, never in diagnostic messages. Ignore detached fields
# and later user edits; do not move focus merely to validate an input.
WATCH = """function() {
    if (!('value' in this) || this.type === 'password' ||
        /one-time-code|password/.test(this.autocomplete || '')) return false;
    const state = {expected: this.value, lost: false};
    this.__studioInputCheck = state;
    this.addEventListener('input', () => { state.expected = this.value; }, {once:true});
    const onBlur = (event) => {
        if (!event.isTrusted) return;
        this.removeEventListener('blur', onBlur);
        const expected = state.expected;
        setTimeout(() => {
            if (this.isConnected && expected && !this.value) state.lost = true;
        }, 0);
    };
    this.addEventListener('blur', onBlur);
    return true;
}"""
CHECK = """function() {
    return !!(this.isConnected && this.__studioInputCheck?.lost);
}"""


def framework_script(method):
    tree = ast.parse(textwrap.dedent(inspect.getsource(method)))
    scripts = [node.value.value for node in ast.walk(tree)
               if isinstance(node, ast.Assign) and isinstance(node.value, ast.Constant)
               and isinstance(node.value.value, str)
               and any(isinstance(t, ast.Name) and t.id == 'framework_events_script' for t in node.targets)]
    if len(scripts) != 1 or scripts[0].count(_BLUR) != 1:
        raise RuntimeError("browser-use input compatibility changed; review adapter before running tasks")
    return scripts[0].replace(_BLUR, "")


async def call(session, object_id, script):
    return await session.cdp_client.send.Runtime.callFunctionOn(
        params={"objectId": object_id, "functionDeclaration": script, "returnByValue": True},
        session_id=session.session_id)


def install():
    global _installed
    if _installed:
        return
    if version("browser-use") != "0.13.14":
        raise RuntimeError("Unsupported browser-use version: input compatibility requires review")
    from browser_use.browser.watchdogs.default_action_watchdog import DefaultActionWatchdog
    script = framework_script(DefaultActionWatchdog._trigger_framework_events)
    original_input = DefaultActionWatchdog._input_text_element_node_impl
    if list(inspect.signature(original_input).parameters) != ['self', 'element_node', 'text', 'clear', 'is_sensitive']:
        raise RuntimeError("browser-use input signature changed; review compatibility adapter")

    async def input_text(self, element_node, text, clear=True, is_sensitive=False):
        result = await original_input(self, element_node, text, clear, is_sensitive)
        # Append mode and formatter differences are valid. A requested nonempty
        # replacement becoming empty is not; never echo values in this error.
        if not is_sensitive and clear and text and isinstance(result, dict) and result.get('actual_value') == '':
            raise BrowserTaskFailure('INPUT_VALUE_LOST', '输入内容未保留；请检查页面，操作未自动重试。')
        return result

    async def trigger(self, object_id, cdp_session):
        result = await call(cdp_session, object_id, script)
        if result.get("exceptionDetails") or not result.get("result", {}).get("value"):
            raise BrowserTaskFailure("INPUT_EVENT_FAILED", "输入事件处理失败；请检查页面，操作未自动重试。")
        result = await call(cdp_session, object_id, WATCH)
        checks = _checks.get()
        if checks is not None and result.get("result", {}).get("value"):
            checks.append((cdp_session, object_id))

    DefaultActionWatchdog._trigger_framework_events = trigger
    DefaultActionWatchdog._input_text_element_node_impl = input_text
    _installed = True


def begin_checks():
    return _checks.set([])


def end_checks(token):
    _checks.reset(token)


async def verify_inputs():
    checks = _checks.get()
    if not checks:
        return
    for session, object_id in list(checks):
        try:
            result = await call(session, object_id, CHECK)
        except Exception as exc:
            # Navigation destroys old DOM handles. Other transport failures must
            # stop execution, not silently permit a subsequent submission.
            if any(s in str(exc) for s in ("Could not find object", "Cannot find context", "Cannot find object")):
                checks.remove((session, object_id))
                continue
            raise BrowserTaskFailure("INPUT_CHECK_FAILED", "无法核验输入状态；请检查页面，不要重复提交。") from exc
        if result.get("result", {}).get("value"):
            raise BrowserTaskFailure("INPUT_VALUE_LOST", "输入内容在切换焦点后丢失；已停止后续操作，请检查页面，不要重复提交。")
        if result.get("exceptionDetails"):
            raise BrowserTaskFailure("INPUT_CHECK_FAILED", "无法核验输入状态；请检查页面，不要重复提交。")
