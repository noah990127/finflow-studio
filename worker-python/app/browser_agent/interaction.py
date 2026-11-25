"""Typed human receipts and bounded, safe diagnostics for the platform adapter."""
from datetime import datetime
from typing import Literal
from zoneinfo import ZoneInfo

InteractionKind = Literal["LOGIN", "INPUT", "APPROVAL", "VERIFICATION"]
KINDS = {"LOGIN", "INPUT", "APPROVAL", "VERIFICATION"}


def current_time(timezone: str = "Asia/Shanghai") -> dict:
    return {"time": datetime.now(ZoneInfo(timezone)).isoformat(timespec="seconds"),
            "timezone": timezone, "source": "platform_system_clock"}


class BrowserTaskFailure(RuntimeError):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def unsuccessful_result(errors, *, terminal_done=False):
    # Do not expose raw model/page output, which may contain secrets or page instructions.
    # A terminal done(success=false) is an outcome failure, not a recovered
    # parser failure from an earlier step. Otherwise inspect only the last step.
    text = str(errors[-1] or "") if errors and not terminal_done else ""
    if "blocked by security policy" in text or "non-allowed" in text:
        return BrowserTaskFailure("DOMAIN_NOT_AUTHORIZED", "访问的网站不在本次授权范围，任务未完成；请核对任务范围，不要重复提交。")
    if any(s in text for s in ("validation error", "Expecting", "ModelProviderError")):
        return BrowserTaskFailure("MODEL_OUTPUT_INVALID", "模型返回的操作格式不合法，任务已停止；已执行的操作不会自动重试。")
    return BrowserTaskFailure("OUTCOME_NOT_VERIFIED", "浏览器未能核验任务成功；请核对实际结果，不要重复提交。")
