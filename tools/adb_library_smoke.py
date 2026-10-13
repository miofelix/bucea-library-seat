#!/usr/bin/env python3
"""Check native library search, time selection and records without submitting a booking.

Requires an installed debug app, an unlocked Android device, campus connectivity and an
already saved, authenticated account. Does not clear app data or enter credentials.

Usage:
    python3 tools/adb_library_smoke.py --serial DEVICE_ID
    python3 tools/adb_library_smoke.py --artifacts .private/native-qa

Screenshots are optional and can contain personal information. Keep them in .private/.
The script never saves or prints the UI hierarchy or its account labels.
"""

from __future__ import annotations

import argparse
from datetime import date
from pathlib import Path
import os
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


PACKAGE = "cn.edu.bucea.library"
COMPONENT = PACKAGE + "/.MainActivity"
ISO_DATE = re.compile(r"\d{4}-\d{2}-\d{2}\Z")
SEAT_LABEL = re.compile(r"\d+[A-Za-z]?\Z")
TIME_LABEL = re.compile(r"(?:[01]\d|2[0-3]):[0-5]\d\Z")
SAFE_LABELS = {
    "首页", "选座", "我的", "查找可用座位", "下一步", "修改时间", "取消",
    "我的预约", "常用座位", "违约记录", "返回首页",
}
FORBIDDEN_LABELS = {
    "确认预约", "签到", "确认签到", "结束使用", "确认结束使用",
    "续约", "确认续约", "修改密码", "暂离", "确认暂离", "返回座位",
    "确认返回座位", "移除", "确认移除", "退出系统", "登录图书馆",
}


class SmokeFailure(Exception):
    """A safe, account-independent diagnostic for a failed acceptance step."""


def find_adb(explicit: str | None) -> str:
    if explicit:
        return explicit
    executable = shutil.which("adb")
    if executable:
        return executable
    for sdk in [os.environ.get("ANDROID_SDK_ROOT"), os.environ.get("ANDROID_HOME"),
                str(Path.home() / "Library/Android/sdk"), str(Path.home() / "Android/Sdk")]:
        if sdk and (Path(sdk) / "platform-tools/adb").is_file():
            return str(Path(sdk) / "platform-tools/adb")
    raise SmokeFailure("adb 未找到；请设置 ANDROID_SDK_ROOT 或使用 --adb。")


def bounds(node: ET.Element) -> tuple[int, int, int, int]:
    coordinates = re.findall(r"-?\d+", node.get("bounds", ""))
    return tuple(map(int, coordinates)) if len(coordinates) == 4 else (0, 0, 0, 0)


def label(node: ET.Element) -> str:
    return node.get("text", "").strip() or node.get("content-desc", "").strip()


def found(value) -> bool:
    # ElementTree leaf elements are false in Boolean contexts, despite being
    # valid matches. Keep element existence separate from list/bool predicates.
    return value is not None if isinstance(value, ET.Element) else bool(value)


class NativeSmoke:
    def __init__(self, adb: str, serial: str | None, artifacts: Path | None, timeout: float):
        self.prefix = [adb] + (["-s", serial] if serial else [])
        self.artifacts = artifacts
        self.timeout = timeout
        self.remote_xml = f"/sdcard/bucea-native-smoke-{os.getpid()}.xml"
        self.root = ET.Element("hierarchy")
        self.parents: dict[ET.Element, ET.Element] = {}
        self.width = 0
        self.height = 0
        self.phase = "设备准备"

    def adb(self, *args: str, timeout: float = 20) -> bytes:
        try:
            result = subprocess.run(self.prefix + list(args), stdout=subprocess.PIPE,
                                    stderr=subprocess.PIPE, timeout=min(timeout, 20), check=False)
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise SmokeFailure("adb 无法完成请求；请检查设备连接。") from exc
        if result.returncode:
            # adb stderr can include identifiers or arbitrary app output; do not echo it.
            raise SmokeFailure("adb 请求失败；请确认仅连接目标设备并已授权 USB 调试。")
        return result.stdout

    def require_unlocked(self) -> None:
        policy = self.adb("shell", "dumpsys", "window", "policy").decode("utf-8", "replace")
        locked = re.search(
            r"(?im)^\s*(?:showing|mShowingLockscreen|mKeyguardShowing|isKeyguardShowing)\s*=\s*true\b",
            policy,
        ) or re.search(r"(?im)^.*keyguard[^\n]*\bshowing\s*=\s*true\b", policy)
        if locked:
            raise SmokeFailure("手机处于锁屏状态；请手动解锁后重新运行。")

    def snapshot(self) -> None:
        self.adb("shell", "uiautomator", "dump", self.remote_xml)
        raw = self.adb("exec-out", "cat", self.remote_xml)
        try:
            start = raw.index(b"<?xml")
            self.root = ET.fromstring(raw[start:])
        except (ValueError, ET.ParseError) as exc:
            raise SmokeFailure("无法读取原生界面，请保持 App 在前台。") from exc
        self.parents = {child: parent for parent in self.root.iter() for child in parent}
        all_bounds = [bounds(node) for node in self.root.iter("node")]
        self.width = max((xy[2] for xy in all_bounds), default=0)
        self.height = max((xy[3] for xy in all_bounds), default=0)
        if not self.width or not self.height:
            raise SmokeFailure("界面尚未就绪，请保持屏幕点亮。")

    def visible(self, node: ET.Element, enabled: bool = True) -> bool:
        x1, y1, x2, y2 = bounds(node)
        if x2 <= max(x1, 0) or y2 <= max(y1, 0) or x1 >= self.width or y1 >= self.height:
            return False
        if node.get("package") != PACKAGE:
            return False
        if enabled:
            current: ET.Element | None = node
            while current is not None:
                if current.get("enabled") == "false":
                    return False
                current = self.parents.get(current)
        return True

    def nodes(self, predicate, enabled: bool = True) -> list[ET.Element]:
        return [node for node in self.root.iter("node") if self.visible(node, enabled) and predicate(node)]

    def exact(self, value: str, bottom: bool = False) -> ET.Element | None:
        matches = self.nodes(lambda node: label(node) == value)
        if bottom:
            matches = [node for node in matches if bounds(node)[1] >= self.height * .7]
        return max(matches, key=lambda node: bounds(node)[1]) if matches else None

    def record_tab(self, value: str) -> ET.Element | None:
        """Distinguish a clickable section tab from its duplicate heading text."""
        matches = self.nodes(lambda node: label(node) == value)
        for node in matches:
            current: ET.Element | None = node
            for _ in range(3):
                if current is None:
                    break
                x1, y1, x2, y2 = bounds(current)
                if current.get("clickable") == "true" and 0 < y2 - y1 <= self.height * .15:
                    return node
                current = self.parents.get(current)
        return None

    def wait(self, predicate, diagnostic: str, timeout: float | None = None):
        deadline = time.monotonic() + min(timeout or self.timeout, 20)
        while time.monotonic() < deadline:
            self.snapshot()
            result = predicate()
            if found(result):
                return result
            time.sleep(.35)
        raise SmokeFailure(diagnostic)

    def tap(self, node: ET.Element, expected: str) -> None:
        """Central allowlist: a future script edit cannot accidentally tap a mutation."""
        if expected in FORBIDDEN_LABELS or not (
            expected in SAFE_LABELS or ISO_DATE.fullmatch(expected) or
            SEAT_LABEL.fullmatch(expected) or TIME_LABEL.fullmatch(expected)
        ):
            raise SmokeFailure("已阻止不在只读验收范围内的控件操作。")
        if label(node) != expected or not self.visible(node):
            raise SmokeFailure("目标控件已变化；验收已停止以避免误操作。")
        # Text semantics can be children of a clickable chip/card. Keep the tap at
        # the verified label's centre so adjacent bookmark/mutation icons are avoided.
        x1, y1, x2, y2 = bounds(node)
        self.adb("shell", "input", "tap", str((max(0, x1) + min(self.width, x2)) // 2),
                 str((max(0, y1) + min(self.height, y2)) // 2))

    def tap_exact(self, value: str, bottom: bool = False) -> None:
        node = self.wait(lambda: self.exact(value, bottom), f"未找到可用的“{value}”控件。")
        self.tap(node, value)

    def scroll(self) -> None:
        scrollables = self.nodes(lambda node: node.get("scrollable") == "true")
        usable = [node for node in scrollables if bounds(node)[3] - bounds(node)[1] >= self.height * .15]
        if usable:
            target = min(usable, key=lambda node: (bounds(node)[2] - bounds(node)[0]) * (bounds(node)[3] - bounds(node)[1]))
            x1, y1, x2, y2 = bounds(target)
            x1, y1, x2, y2 = max(0, x1), max(0, y1), min(self.width, x2), min(self.height, y2)
        else:
            x1, y1, x2, y2 = 0, int(self.height * .23), self.width, int(self.height * .77)
        x = (x1 + x2) // 2
        self.adb("shell", "input", "swipe", str(x), str(int(y1 + (y2-y1) * .78)),
                 str(x), str(int(y1 + (y2-y1) * .32)), "350")

    def find_scrolling(self, predicate, diagnostic: str, max_swipes: int = 6):
        deadline = time.monotonic() + 20
        for step in range(max_swipes + 1):
            self.snapshot()
            result = predicate()
            if found(result):
                return result
            if step == max_swipes or time.monotonic() >= deadline:
                break
            self.scroll()
            time.sleep(.25)
        raise SmokeFailure(diagnostic)

    def screenshot(self, name: str) -> None:
        if self.artifacts:
            self.artifacts.mkdir(parents=True, exist_ok=True)
            (self.artifacts / (name + ".png")).write_bytes(self.adb("exec-out", "screencap", "-p"))

    def pass_phase(self, name: str) -> None:
        self.screenshot(name)
        print(f"PASS {name}", flush=True)

    def time_choice(self, section: str) -> ET.Element | None:
        heading = self.exact(section)
        if heading is None:
            return None
        heading_y = bounds(heading)[3]
        next_heading = self.exact("结束时间") if section == "开始时间" else None
        next_y = bounds(next_heading)[1] if next_heading is not None else self.height
        matches = self.nodes(lambda node: TIME_LABEL.fullmatch(label(node)) is not None and
                             bounds(node)[1] >= heading_y and bounds(node)[3] <= next_y)
        return min(matches, key=lambda node: (bounds(node)[1], bounds(node)[0])) if matches else None

    def seat_choice(self) -> ET.Element | None:
        matches = self.nodes(lambda node: SEAT_LABEL.fullmatch(label(node)) is not None)
        for node in sorted(matches, key=lambda element: (bounds(element)[1], bounds(element)[0])):
            # Pagination contains bare integers too. A seat card owns a bookmark
            # affordance; checking that semantic ancestor avoids tapping page numbers.
            parent = self.parents.get(node)
            for _ in range(3):
                if parent is None:
                    break
                texts = [label(child) for child in parent.iter("node")]
                if any(text.startswith(("收藏 ", "取消收藏 ")) for text in texts):
                    return node
                parent = self.parents.get(parent)
        return None

    def run(self) -> None:
        self.require_unlocked()
        self.adb("shell", "am", "start", "-n", COMPONENT)
        self.phase = "原生选座"
        # Restore the top of the feature screen after a previous smoke run. A
        # retained modal must be dismissed by the user; never guess its action.
        self.tap_exact("首页", bottom=True)
        self.tap_exact("选座", bottom=True)
        self.wait(lambda: self.exact("查找可用座位") is not None and self.nodes(lambda node: ISO_DATE.fullmatch(label(node)) is not None),
                  "选座未就绪；请先在 App 中保存账号并完成登录，确认已连接校园网。")
        raw_today = self.adb("shell", "date", "+%Y-%m-%d").decode().strip()
        try:
            today = date.fromisoformat(raw_today)
        except ValueError as exc:
            raise SmokeFailure("无法读取设备日期，请检查系统时间。") from exc
        dates = [(date.fromisoformat(label(node)), node) for node in self.nodes(lambda node: ISO_DATE.fullmatch(label(node)) is not None)]
        future_dates = sorted(((day, node) for day, node in dates if day > today), key=lambda item: item[0])
        if not future_dates:
            raise SmokeFailure("学校当前未提供未来预约日期；验收已停止，未创建预约。")
        selected_date, selected_node = future_dates[0]
        self.tap(selected_node, selected_date.isoformat())
        self.tap_exact("查找可用座位")
        self.wait(lambda: self.exact("查找可用座位"),
                  "座位查询未完成；请确认校园网连接，或稍后重试。")
        self.find_scrolling(lambda: self.nodes(lambda node: label(node).startswith("已加载 ")),
                            "查询未显示座位结果；请检查校园网或筛选条件。")
        self.pass_phase("01-native-search")

        self.phase = "选择服务器时段"
        seat_node = self.find_scrolling(self.seat_choice, "查询未显示可用座位卡片；可手动检查筛选条件。")
        selected_seat = label(seat_node)
        self.tap(seat_node, selected_seat)
        self.wait(lambda: self.exact("选择可用时段"), "未打开座位时段选择。")
        start_node = self.wait(lambda: self.time_choice("开始时间"), "该座位没有可用开始时间，或时段查询未完成。")
        selected_start = label(start_node)
        self.tap(start_node, selected_start)
        # Ending times are loaded asynchronously. Wait for the section's actual
        # options before scrolling the bounded dialog viewport.
        deadline = time.monotonic() + self.timeout
        end_node = None
        swipes = 0
        while time.monotonic() < deadline:
            self.snapshot()
            end_node = self.time_choice("结束时间")
            if end_node is not None:
                break
            # Keep a visible ending heading in place while its server choices load.
            if self.exact("结束时间") is None and swipes < 6:
                self.scroll()
                swipes += 1
            time.sleep(.4)
        if end_node is None:
            raise SmokeFailure("未显示可用结束时间；验收已停止，未提交预约。")
        selected_end = label(end_node)
        self.tap(end_node, selected_end)
        self.tap_exact("下一步")

        self.phase = "核对预约预览"
        self.wait(lambda: self.exact("确认这次预约"), "未打开预约确认预览。")
        self.snapshot()
        texts = [label(node) for node in self.nodes(lambda _: True, enabled=False)]
        if selected_date.isoformat() not in texts:
            raise SmokeFailure("预约预览未显示所选日期。")
        if not any(text.startswith(selected_seat + " · ") for text in texts):
            raise SmokeFailure("预约预览中的座位与选择不一致。")
        normalized = [re.sub(r"\s+", "", text) for text in texts]
        if selected_start + "—" + selected_end not in normalized:
            raise SmokeFailure("预约预览未完整显示所选开始和结束时间。")
        if self.exact("确认预约") is None:
            raise SmokeFailure("预约预览缺少可用确认按钮；验收未执行提交。")
        self.pass_phase("02-reservation-review-no-submit")
        self.tap_exact("修改时间")
        self.tap_exact("取消")

        self.phase = "预约收藏与违约记录"
        self.adb("shell", "input", "keyevent", "KEYCODE_BACK")
        self.tap_exact("我的", bottom=True)
        self.wait(lambda: self.exact("我的图书馆"), "未打开原生个人图书馆页面。")
        for index, title in enumerate(["我的预约", "常用座位", "违约记录"], start=3):
            self.find_scrolling(lambda value=title: self.selected_tab(value) or self.record_tab(value), f"未找到“{title}”标签。")
            if not self.selected_tab(title):
                node = self.record_tab(title)
                if node is None:
                    raise SmokeFailure(f"“{title}”标签暂不可操作。")
                self.tap(node, title)
            # The section header shares the tab label. The selected state is the
            # authoritative signal, followed by its original-page entry.
            self.wait(lambda value=title: self.selected_tab(value) and self.exact("查看完整页面") is not None,
                      f"“{title}”页面尚未同步完成。")
            self.pass_phase(f"0{index}-" + ["reservations", "favorites", "violations"][index-3])

    def selected_tab(self, title: str) -> bool:
        for node in self.nodes(lambda element: label(element) == title):
            current: ET.Element | None = node
            for _ in range(3):
                if current is None:
                    break
                if current.get("selected") == "true":
                    return True
                current = self.parents.get(current)
        return False

    def cleanup(self) -> None:
        try:
            self.adb("shell", "rm", "-f", self.remote_xml, timeout=5)
        except SmokeFailure:
            pass


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", help="adb device serial; omit only when a single device is connected")
    parser.add_argument("--adb", help="path to adb executable")
    parser.add_argument("--timeout", type=float, default=20, help="maximum wait per UI phase, 1–20 seconds (default 20)")
    parser.add_argument("--artifacts", type=Path, nargs="?", const=Path(".private/native-qa"), help="optional screenshot directory (default .private/native-qa when flag is supplied)")
    args = parser.parse_args()
    if not 1 <= args.timeout <= 20:
        parser.error("--timeout must be between 1 and 20 seconds")
    smoke = None
    try:
        smoke = NativeSmoke(find_adb(args.adb), args.serial, args.artifacts, args.timeout)
        smoke.run()
        return 0
    except SmokeFailure as exc:
        phase = smoke.phase if smoke else "设备准备"
        print(f"FAIL {phase}: {exc}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("STOP 验收已由用户中止。", file=sys.stderr)
        return 130
    finally:
        if smoke:
            smoke.cleanup()


if __name__ == "__main__":
    raise SystemExit(main())
