#!/usr/bin/env python3
"""Check native library search, time selection and records without submitting a booking.

Requires an installed debug app, an unlocked Android device, campus connectivity and an
already saved, authenticated account. Does not clear app data or enter credentials.

Usage:
    python3 tools/adb_library_smoke.py --serial DEVICE_ID
    python3 tools/adb_library_smoke.py --artifacts .private/native-qa
    python3 tools/adb_library_smoke.py --full --artifacts .private/full-native-qa
    python3 tools/adb_library_smoke.py --map-only --artifacts .private/map-native-qa

--full additionally inspects native filter choices, server floor/room maps, the
accessible seat list, and the unimplemented password-change entry. It never submits a
booking, account change, reservation action, password change, or sign-out.

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
    "首页", "选座", "我的", "账号", "查找可用座位", "下一步", "修改时间", "取消",
    "我的预约", "收藏座位", "常用座位", "违约记录", "返回首页",
    "布局选座", "地图", "列表", "关闭", "适应区域", "修改图书馆密码",
    "时间、电源与靠窗", "收起筛选条件", "场馆", "阅览区域", "使用时长",
    "开始时间", "结束时间", "选择开始时间", "选择结束时间", "电源", "靠窗",
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
        # Include only a known command category, never arbitrary arguments,
        # captured stderr, device identifiers, account data or UI text.
        if args[:3] == ("shell", "uiautomator", "dump"):
            category = "读取界面"
        elif args[:2] == ("exec-out", "cat"):
            category = "读取界面结果"
        elif args[:2] == ("shell", "input"):
            category = "操作已验证控件"
        elif args[:2] == ("shell", "date"):
            category = "读取设备日期"
        elif args[:3] == ("shell", "dumpsys", "window"):
            category = "检查锁屏状态"
        elif args[:3] == ("shell", "am", "start"):
            category = "打开原生 App"
        elif args[:2] == ("exec-out", "screencap"):
            category = "保存界面截图"
        else:
            category = "设备请求"
        try:
            result = subprocess.run(self.prefix + list(args), stdout=subprocess.PIPE,
                                    stderr=subprocess.PIPE, timeout=min(timeout, 20), check=False)
        except subprocess.TimeoutExpired as exc:
            raise SmokeFailure(f"adb {category}超时；请检查设备连接。") from exc
        except OSError as exc:
            raise SmokeFailure(f"adb {category}无法启动；请检查设备连接。") from exc
        if result.returncode:
            # adb stderr can include identifiers or arbitrary app output; do not echo it.
            raise SmokeFailure(f"adb {category}失败（退出码 {result.returncode}）；请确认设备已授权且没有其他 UI 测试同时运行。")
        return result.stdout

    def require_unlocked(self) -> None:
        policy = self.adb("shell", "dumpsys", "window", "policy").decode("utf-8", "replace")
        locked = re.search(
            r"(?im)^\s*(?:showing|mIsShowing|mShowingLockscreen|mKeyguardShowing|isKeyguardShowing)\s*=\s*true\b",
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
        # Compose can expose a tab's text without a clickable accessibility
        # ancestor. Its native section labels still share one horizontal
        # row; the duplicate content heading has no such neighbouring labels.
        titles = {"我的预约", "收藏座位", "常用座位", "违约记录"}
        if value not in titles:
            return None
        row_labels = self.nodes(lambda node: label(node) in titles)
        for node in sorted(matches, key=lambda item: bounds(item)[1]):
            x1, y1, x2, y2 = bounds(node)
            if not 0 < y2 - y1 <= self.height * .1 or not 0 < x2 - x1 <= self.width * .5:
                continue
            centre = (y1 + y2) / 2
            tolerance = max(8, (y2 - y1) * .65)
            neighbours = [item for item in row_labels if
                          abs((bounds(item)[1] + bounds(item)[3]) / 2 - centre) <= tolerance and
                          0 < bounds(item)[3] - bounds(item)[1] <= self.height * .1]
            if {label(item) for item in neighbours} == titles:
                return node
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
        if (label(node) != expected and node.get("content-desc") != expected) or not self.visible(node):
            raise SmokeFailure("目标控件已变化；验收已停止以避免误操作。")
        # Text semantics can be children of a clickable chip/card. Keep the tap at
        # the verified label's centre so adjacent bookmark/mutation icons are avoided.
        self.tap_center(node)

    def tap_center(self, node: ET.Element) -> None:
        x1, y1, x2, y2 = bounds(node)
        self.adb("shell", "input", "tap", str((max(0, x1) + min(self.width, x2)) // 2),
                 str((max(0, y1) + min(self.height, y2)) // 2))

    def tap_exact(self, value: str, bottom: bool = False) -> None:
        node = self.wait(lambda: self.exact(value, bottom), f"未找到可用的“{value}”控件。")
        self.tap(node, value)

    def scroll(self, up: bool = False) -> None:
        scrollables = self.nodes(lambda node: node.get("scrollable") == "true")
        usable = [node for node in scrollables if bounds(node)[3] - bounds(node)[1] >= self.height * .15]
        if usable:
            target = min(usable, key=lambda node: (bounds(node)[2] - bounds(node)[0]) * (bounds(node)[3] - bounds(node)[1]))
            x1, y1, x2, y2 = bounds(target)
            x1, y1, x2, y2 = max(0, x1), max(0, y1), min(self.width, x2), min(self.height, y2)
        else:
            x1, y1, x2, y2 = 0, int(self.height * .23), self.width, int(self.height * .77)
        x = (x1 + x2) // 2
        begin, end = (.32, .78) if up else (.78, .32)
        self.adb("shell", "input", "swipe", str(x), str(int(y1 + (y2-y1) * begin)),
                 str(x), str(int(y1 + (y2-y1) * end)), "350")

    def find_scrolling(self, predicate, diagnostic: str, max_swipes: int = 6, up: bool = False):
        deadline = time.monotonic() + 20
        for step in range(max_swipes + 1):
            self.snapshot()
            result = predicate()
            if found(result):
                return result
            if step == max_swipes or time.monotonic() >= deadline:
                break
            self.scroll(up)
            time.sleep(.25)
        raise SmokeFailure(diagnostic)

    def screenshot(self, name: str) -> None:
        if self.artifacts:
            self.artifacts.mkdir(parents=True, exist_ok=True)
            (self.artifacts / (name + ".png")).write_bytes(self.adb("exec-out", "screencap", "-p"))

    def pass_phase(self, name: str) -> None:
        self.screenshot(name)
        print(f"PASS {name}", flush=True)

    def select_time(self, section: str) -> str:
        """Choose a server-provided time through the compact native field."""
        description = "选择" + section
        trigger = self.wait(
            lambda: next(iter(self.nodes(lambda node: node.get("content-desc") == description)), None),
            "时段选择入口尚未就绪。",
        )
        self.tap(trigger, description)
        self.wait(lambda: self.exact("关闭"), "原生时段选项未打开。")
        choices = self.nodes(lambda node: TIME_LABEL.fullmatch(label(node)) is not None)
        if not choices:
            raise SmokeFailure("该座位没有可用服务器时段；验收未提交预约。")
        choice = min(choices, key=lambda node: (bounds(node)[1], bounds(node)[0]))
        value = label(choice)
        self.tap(choice, value)
        self.wait(lambda: self.exact("选择可用时段"), "时段选项未返回预约面板。")
        return value

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
        self.find_scrolling(lambda: self.nodes(lambda node: label(node).startswith(("已加载 ", "本页显示 "))),
                            "查询未显示座位结果；请检查校园网或筛选条件。")
        self.pass_phase("01-native-search")

        self.phase = "选择服务器时段"
        seat_node = self.find_scrolling(self.seat_choice, "查询未显示可用座位卡片；可手动检查筛选条件。")
        selected_seat = label(seat_node)
        self.tap(seat_node, selected_seat)
        self.wait(lambda: self.exact("选择可用时段"), "未打开座位时段选择。")
        selected_start = self.select_time("开始时间")
        selected_end = self.select_time("结束时间")
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
        for index, title in enumerate(["我的预约", "收藏座位", "常用座位", "违约记录"], start=3):
            self.find_scrolling(lambda value=title: self.selected_tab(value) or self.record_tab(value), f"未找到“{title}”标签。")
            if not self.selected_tab(title):
                node = self.record_tab(title)
                if node is None:
                    raise SmokeFailure(f"“{title}”标签暂不可操作。")
                self.tap(node, title)
            # Tab controls are disabled while syncing. selected_tab only inspects
            # enabled nodes; the refresh icon can be above the visible scroll area.
            self.wait(lambda value=title: self.selected_tab(value),
                      f"“{title}”页面尚未同步完成。")
            if self.exact("暂时无法同步") is not None:
                raise SmokeFailure(f"“{title}”未能同步校园数据。")
            self.pass_phase(f"0{index}-" + ["reservations", "favorites", "frequent", "violations"][index-3])

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

    def date_value(self, node: ET.Element) -> str | None:
        for value in [node.get("content-desc", "").strip(), node.get("text", "").strip()]:
            if ISO_DATE.fullmatch(value):
                return value
        return None

    def clickable_ancestor(self, node: ET.Element) -> ET.Element | None:
        current: ET.Element | None = node
        for _ in range(4):
            if current is None:
                break
            if current.get("clickable") == "true":
                return current
            current = self.parents.get(current)
        return None

    def map_room_choices(self) -> list[ET.Element]:
        dates = self.nodes(lambda node: self.date_value(node) is not None)
        date_bottom = max((bounds(node)[3] for node in dates), default=0)
        choices = []
        seen = set()
        for node in self.nodes(lambda item: bool(label(item)) and self.date_value(item) is None):
            parent = self.clickable_ancestor(node)
            if parent is None or parent.get("checkable") != "true":
                continue
            if bounds(node)[1] <= date_bottom or label(node) in FORBIDDEN_LABELS or label(node) in SAFE_LABELS:
                continue
            if label(node) not in seen:
                choices.append(node)
                seen.add(label(node))
        return sorted(choices, key=lambda node: (bounds(node)[1], bounds(node)[0]))

    def tap_observed_choice(self, node: ET.Element, kind: str) -> None:
        """Only allow dynamic data from a verified native map/filter context."""
        if not self.visible(node) or label(node) in FORBIDDEN_LABELS:
            raise SmokeFailure("已阻止不在只读验收范围内的动态选项。")
        if kind == "date":
            valid = self.date_value(node) is not None and self.exact("刷新座位地图") is not None
        elif kind == "room":
            valid = self.exact("刷新座位地图") is not None and node in self.map_room_choices()
        elif kind == "floor":
            valid = self.exact("选择楼层") is not None and self.exact("关闭") is not None and self.clickable_ancestor(node) is not None
        else:
            valid = False
        if not valid:
            raise SmokeFailure("动态选项的原生界面上下文已变化，验收已停止。")
        self.tap_center(node)

    def require_map_ready(self) -> None:
        self.wait(lambda: self.exact("刷新座位地图"), "地图数据未就绪，请检查校园网连接。")
        if self.exact("完成验证") is not None:
            raise SmokeFailure("学校要求图片验证；请在 App 完成后重新运行验收。")
        if self.exact("重试") is not None:
            raise SmokeFailure("地图数据同步失败；请手动检查连接后重试。")

    def map_floor_button(self) -> ET.Element | None:
        dates = self.nodes(lambda node: self.date_value(node) is not None)
        date_bottom = max((bounds(node)[3] for node in dates), default=0)
        rooms = self.map_room_choices()
        rooms_top = min((bounds(node)[1] for node in rooms), default=self.height * .75)
        candidates = []
        seen = set()
        for node in self.nodes(lambda item: bool(label(item))):
            parent = self.clickable_ancestor(node)
            if parent is None or parent.get("checkable") == "true":
                continue
            xy = bounds(parent)
            if date_bottom <= xy[1] < rooms_top and xy[0] > self.width * .42 and xy[2] < self.width * .98:
                if xy not in seen:
                    candidates.append(node)
                    seen.add(xy)
        return min(candidates, key=lambda node: bounds(node)[1]) if candidates else None

    def open_floor_options(self) -> list[str]:
        button = self.find_scrolling(
            lambda: self.map_floor_button() if self.exact("刷新座位地图") is not None else None,
            "未找到原生楼层选择按钮。", up=True,
        )
        if self.exact("刷新座位地图") is None or self.clickable_ancestor(button) is None:
            raise SmokeFailure("楼层选择上下文已变化。")
        self.tap_center(button)
        self.wait(lambda: self.exact("选择楼层"), "未打开楼层选择。")
        options = []
        for node in self.nodes(lambda item: bool(label(item)) and label(item) not in {"选择楼层", "关闭"}):
            if self.clickable_ancestor(node) is not None and label(node) not in FORBIDDEN_LABELS:
                options.append(label(node))
        return list(dict.fromkeys(options))

    def run_filter_checks(self) -> None:
        self.phase = "原生高级筛选"
        self.tap_exact("首页", bottom=True)
        self.tap_exact("选座", bottom=True)
        self.wait(lambda: self.exact("查找可用座位"), "原生筛选页面未就绪。")
        if self.exact("时间、电源与靠窗") is not None:
            self.tap_exact("时间、电源与靠窗")
        for title in ["场馆", "阅览区域", "开始时间", "结束时间", "使用时长", "电源", "靠窗"]:
            node = self.find_scrolling(lambda value=title: self.exact(value), "高级筛选字段未完整显示。")
            self.tap(node, title)
            self.wait(lambda: self.exact("关闭"), "筛选选项未打开。")
            choices = self.nodes(lambda item: item.get("class") == "android.widget.RadioButton" or item.get("checkable") == "true")
            if not choices:
                raise SmokeFailure("原生筛选没有提供可选择项。")
            self.tap_exact("关闭")
        self.pass_phase("06-native-filter-options")

    def switch_map_mode(self, target: str) -> None:
        """Verify selection for the two persistent map/list segment buttons."""
        desired = "列表" if target == "list" else "地图"

        def selected() -> bool:
            node = self.exact(desired)
            for _ in range(4):
                if node is None:
                    return False
                if node.get("selected") == "true" or node.get("checked") == "true":
                    return True
                node = self.parents.get(node)
            return False

        node = self.find_scrolling(lambda: self.exact(desired), "未找到地图显示方式切换按钮。", up=True)
        if not selected():
            self.tap(node, desired)
        self.wait(selected, "地图显示方式未切换；已停止验收，未执行任何预约操作。")

    def run_map_checks(self) -> None:
        self.phase = "原生地图楼层与区域"
        self.tap_exact("首页", bottom=True)
        self.tap_exact("选座", bottom=True)
        self.tap_exact("布局选座")
        self.require_map_ready()
        raw_today = self.adb("shell", "date", "+%Y-%m-%d").decode().strip()
        try:
            today = date.fromisoformat(raw_today)
        except ValueError as exc:
            raise SmokeFailure("无法读取设备日期。") from exc
        dates = self.nodes(lambda node: self.date_value(node) is not None)
        future = [node for node in dates if date.fromisoformat(self.date_value(node)) > today]
        if future:
            self.tap_observed_choice(min(future, key=lambda node: self.date_value(node)), "date")
            self.require_map_ready()
        floor_labels = self.open_floor_options()
        self.tap_exact("关闭")
        if not floor_labels:
            raise SmokeFailure("学校未返回可用楼层。")
        for index, floor in enumerate(floor_labels[:6], start=1):
            self.open_floor_options()
            node = self.exact(floor)
            if node is None:
                raise SmokeFailure("服务端楼层选项已变化。")
            self.tap_observed_choice(node, "floor")
            self.require_map_ready()
            rooms = self.map_room_choices()
            if not rooms:
                print(f"SKIP native-map-floor-{index}: no-school-room", flush=True)
                continue
            self.tap_observed_choice(rooms[0], "room")
            self.require_map_ready()
            canvas = self.find_scrolling(lambda: self.nodes(lambda node: "座位地图，双指缩放" in node.get("content-desc", "")),
                                         "原生地图画布未显示。")
            self.pass_phase(f"07-native-map-floor-{index}-canvas")
            # Pan the native canvas, then reset fit. No seat/action is activated.
            x1, y1, x2, y2 = bounds(canvas[0])
            self.adb("shell", "input", "swipe", str(int(x1+(x2-x1)*.65)), str((y1+y2)//2),
                     str(int(x1+(x2-x1)*.35)), str((y1+y2)//2), "400")
            reset = self.find_scrolling(lambda: self.exact("适应区域"), "未找到地图适应区域按钮。", up=True)
            self.tap(reset, "适应区域")
            self.switch_map_mode("list")
            self.find_scrolling(lambda: self.nodes(lambda node: SEAT_LABEL.fullmatch(label(node)) is not None),
                                "原生地图列表没有显示座位。")
            self.pass_phase(f"08-native-map-floor-{index}-list")
            self.switch_map_mode("map")

    def run_password_unimplemented_check(self) -> None:
        """Check that the unavailable entry shows a notice and opens no form."""
        self.phase = "未实现密码修改入口"
        self.adb("shell", "input", "keyevent", "KEYCODE_BACK")
        self.tap_exact("账号", bottom=True)
        opener = self.find_scrolling(lambda: self.exact("修改图书馆密码"), "未找到原生密码修改入口。")
        self.tap(opener, "修改图书馆密码")
        toast_seen = False
        deadline = time.monotonic() + min(self.timeout, 6)
        while time.monotonic() < deadline:
            self.snapshot()
            if self.nodes(lambda node: label(node) in {"当前密码", "新密码", "再次输入新密码", "修改密码"}, enabled=False):
                raise SmokeFailure("未实现的密码入口不应打开表单或提供提交操作。")
            # Android can expose a transient Toast under another system package.
            # Inspect it in memory without logging the hierarchy or notice text.
            if any("未实现" in label(node) for node in self.root.iter("node")):
                toast_seen = True
                break
            time.sleep(.2)
        self.wait(lambda: self.exact("账号管理"), "密码入口应保持在账号管理页面。")
        self.pass_phase("09-password-entry-no-form-or-submit")
        if toast_seen:
            self.pass_phase("09-password-unimplemented-notice")
        else:
            print("SKIP password-unimplemented-notice: transient-toast-not-observed-use-instrumentation", flush=True)

    def run_full(self) -> None:
        """Bounded additional native UX checks; intentionally omits real mutations."""
        self.run_filter_checks()
        self.run_map_checks()
        self.run_password_unimplemented_check()

    def cleanup(self) -> None:
        try:
            self.adb("shell", "rm", "-f", self.remote_xml, timeout=5)
        except SmokeFailure:
            pass


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", help="adb device serial; omit only when a single device is connected")
    parser.add_argument("--adb", help="path to adb executable")
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--full", action="store_true", help="also inspect native filters, all returned map floors (up to 6), and the unimplemented password entry without form submission")
    modes.add_argument("--map-only", action="store_true", help="only inspect native map floors and canvas/list switching, without repeating search acceptance")
    parser.add_argument("--timeout", type=float, default=20, help="maximum wait per UI phase, 1–20 seconds (default 20)")
    parser.add_argument("--artifacts", type=Path, nargs="?", const=Path(".private/native-qa"), help="optional screenshot directory (default .private/native-qa when flag is supplied)")
    args = parser.parse_args()
    if not 1 <= args.timeout <= 20:
        parser.error("--timeout must be between 1 and 20 seconds")
    smoke = None
    try:
        smoke = NativeSmoke(find_adb(args.adb), args.serial, args.artifacts, args.timeout)
        if args.map_only:
            smoke.require_unlocked()
            smoke.adb("shell", "am", "start", "-n", COMPONENT)
            smoke.run_map_checks()
        else:
            smoke.run()
            if args.full:
                smoke.run_full()
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
