"""No-progress watchdog. Transport keepalives are deliberately not progress."""
import asyncio
import json


class ActivityWatch:
    def __init__(self, root, idle_seconds, tool_seconds):
        self.path = root / "activity.json"
        self.idle_seconds = idle_seconds
        self.tool_seconds = tool_seconds
        self.last = asyncio.get_running_loop().time()
        self.stamp = None
        self.active = False
        self.tool_started = self.last

    def progress(self):
        self.last = asyncio.get_running_loop().time()

    def check(self):
        now = asyncio.get_running_loop().time()
        try:
            stamp = self.path.stat().st_mtime_ns
            if stamp != self.stamp:
                value = json.loads(self.path.read_text())
                self.stamp = stamp
                self.active = value.get("state") == "running"
                self.tool_started = now
                self.last = now
        except (FileNotFoundError, ValueError):
            pass
        if self.active:
            if now - self.tool_started > self.tool_seconds:
                raise RuntimeError(f"工具调用超过 {self.tool_seconds} 秒，已停止本轮；已保存产物保留")
        elif now - self.last > self.idle_seconds:
            raise RuntimeError(f"模型连续 {self.idle_seconds} 秒未返回有效进展，已停止本轮；请检查连接或稍后继续")
