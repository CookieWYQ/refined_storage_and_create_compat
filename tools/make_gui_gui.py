#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
make_gui_gui.py - Minecraft GUI 背景 / 槽位 / 按钮 / 文本 通用编辑器（图形界面版）

用法（直接运行即可打开界面）：
    python tools/make_gui_gui.py

交互：
  * 顶部“工具”切换当前放置元素：槽位 / 按钮 / 文本；也可在画布任意处右键，
    从菜单中“在此添加 槽位/按钮/文本”。
  * 空白处按住拖动 = 框选（命中槽、按钮、文本任意元素）；
  * 点住任一选中元素拖动 = 整体移动（自动吸附 18px，可关）；玩家背包/快捷栏/插件列按整组添加，
    点组内任意一格即整组一起移动，右键组可“解组/整组偏移/删除整组”；
  * 选中“按钮/文本”后拖动其四角/四边小方块 = 拉伸尺寸；
  * 单击 = 选中；Ctrl+单击 = 加选/减选；右键 = 上下文菜单（添加/编辑/解组/删除）；
  * 双击元素 = 编辑（按钮/文本添加后自动弹出编辑框）；双击空白 = 按当前工具放置新元素；
  * Ctrl + 滚轮 = 视图放大/缩小；网格吸附可开关。
  * 文本支持 MC 富文本编辑：B/I/U/K 格式按钮与 16 色块直接插入 § 代码；另可勾选“竖排”逐字向下；
    任意 RGB 基础色用取色器选择（行内变色仍须 § 代码）。
  * “生成 Java 控件代码”输出槽位/按钮/文本对应的代码（精灵坐标 +1 = Menu 坐标）。

素材：背景 bg.png（可拉伸）、槽位 slot.png 均位于项目 pic/ 目录。
"""
import argparse
import base64
import json
import math
import os
import sys
import tkinter as tk
from tkinter import ttk, messagebox, filedialog, scrolledtext, colorchooser

from PIL import Image, ImageTk
import tkinter.font as tkfont
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
DEFAULT_OUT = os.path.join(PROJECT_ROOT, "src", "main", "resources", "assets",
                           "rs_create_compat", "textures", "gui", "gui_custom.png")

if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

import make_gui_bg as MGB

MAR = 4          # 画布内容外边距
GRID = 18
GRIP = 12        # 拉伸柄（界面尺寸）画布像素尺寸
ZOOM_MIN, ZOOM_MAX, ZOOM_STEP = 0.5, 4.0, 1.15
HANDLE = 9       # 元素选中后的调整柄命中半径（画布像素）

# MC § 颜色表
MC_COLORS = {
    "0": "#000000", "1": "#0000AA", "2": "#00AA00", "3": "#00AAAA",
    "4": "#AA0000", "5": "#AA00AA", "6": "#FFAA00", "7": "#AAAAAA",
    "8": "#555555", "9": "#5555FF", "a": "#55FF55", "b": "#55FFFF",
    "c": "#FF5555", "d": "#FF55FF", "e": "#FFFF55", "f": "#FFFFFF",
}
# 取色面板顺序（代码 → 界面文案）
MC_CHIPS = [("0", "黑"), ("1", "深蓝"), ("2", "深绿"), ("3", "深青"),
            ("4", "深红"), ("5", "深紫"), ("6", "金"), ("7", "灰"),
            ("8", "深灰"), ("9", "蓝"), ("a", "绿"), ("b", "青"),
            ("c", "红"), ("d", "紫红"), ("e", "黄"), ("f", "白")]

WIDGET_DIR = os.path.join(PROJECT_ROOT, "pic", "widget")
BTN_FILES = {
    "normal": "button.png",
    "hover": "button_highlighted.png",
    "disabled": "button_disabled.png",
}


def nine_slice_widget(w, h, z, path):
    """把官方按钮贴图 button*.png（200x20）按给定坐标九宫格拉伸到目标 w×h（含缩放 z）。

    坐标（素材内）：
      左段   x 0..2   （3px）
      右段   x 197..199（3px）
      上段   y 0..2   （3px）
      下段   y 16..19 （4px）
    中间区域随目标尺寸双向拉伸 —— 与背景图 draw_container 同思路：
    先按坐标裁出 9 块，再各自 resize 后拼接，绝不整体放大整张素材。
    """
    try:
        src = Image.open(path).convert("RGBA")
    except Exception:
        return None
    W, H = src.size  # 200 x 20
    L, R, T, B = 3, 3, 3, 4   # 与上述坐标一致
    lpx = max(1, int(round(L * z)))
    rpx = max(1, int(round(R * z)))
    tpx = max(1, int(round(T * z)))
    bpx = max(1, int(round(B * z)))
    dw = max(1, int(round(w * z)))
    dh = max(1, int(round(h * z)))
    mid_w = max(0, dw - lpx - rpx)
    mid_h = max(0, dh - tpx - bpx)
    dst = Image.new("RGBA", (dw, dh), (0, 0, 0, 0))

    def paste(sx0, sy0, sx1, sy1, dx, dy, rw, rh):
        if rw <= 0 or rh <= 0:
            return
        region = src.crop((sx0, sy0, sx1, sy1))
        region = region.resize((rw, rh), Image.NEAREST)
        dst.paste(region, (dx, dy))

    cx0, cx1 = L, W - R          # 中间横段原始 x: 3..197
    cy0, cy1 = T, H - B          # 中间竖段原始 y: 3..16
    # 四角（保持原始像素大小，只随视图缩放 z）
    paste(0, 0, L, T, 0, 0, lpx, tpx)                    # 左上
    paste(cx1, 0, W, T, lpx + mid_w, 0, rpx, tpx)         # 右上
    paste(0, cy1, L, H, 0, tpx + mid_h, lpx, bpx)         # 左下
    paste(cx1, cy1, W, H, lpx + mid_w, tpx + mid_h, rpx, bpx)  # 右下
    # 四边（单方向拉伸）
    paste(cx0, 0, cx1, T, lpx, 0, mid_w, tpx)             # 上
    paste(cx0, cy1, cx1, H, lpx, tpx + mid_h, mid_w, bpx)  # 下
    paste(0, cy0, L, cy1, 0, tpx, lpx, mid_h)             # 左
    paste(cx1, cy0, W, cy1, lpx + mid_w, tpx, rpx, mid_h)  # 右
    # 中心（双向拉伸）
    paste(cx0, cy0, cx1, cy1, lpx, tpx, mid_w, mid_h)
    return dst


def _snap(value, offset, grid=GRID):
    if grid <= 0:
        return int(round(value))
    return int(offset + round((value - offset) / float(grid)) * grid)


def _default_font():
    """返回 (family, size)。Python 3.13 的 actual() 返回 dict，老版本返回 tuple，兼容两者。"""
    try:
        info = tkfont.nametofont("TkDefaultFont").actual()
        if isinstance(info, dict):
            family = info.get("family", "Segoe UI")
            size = info.get("size")
            return (family, size if isinstance(size, int) else 9)
        return (info[0], info[1] if len(info) > 1 else 9)
    except Exception:
        return ("Segoe UI", 9)


def _font_spec(size, weight="normal", slant="roman"):
    family = _default_font()[0]
    return (family, -max(6, int(size)), weight, slant)


def rich_runs(text, base="#FFFFFF"):
    """把带 § 代码的文本解析为 [(substr, hexcolor, weight, slant, underline)]。"""
    runs = []
    color = base
    weight = "normal"
    slant = "roman"
    underline = False
    buf = []
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if ch == "§" and i + 1 < n:
            if buf:
                runs.append(("".join(buf), color, weight, slant, underline))
                buf = []
            code = text[i + 1].lower()
            i += 2
            if code in MC_COLORS:
                color = MC_COLORS[code]
            elif code == "l":
                weight = "bold"
            elif code == "o":
                slant = "italic"
            elif code == "n":
                underline = True
            elif code == "r":
                color, weight, slant, underline = base, "normal", "roman", False
            continue
        buf.append(ch)
        i += 1
    if buf:
        runs.append(("".join(buf), color, weight, slant, underline))
    return runs


def _button_bg(w, h, z):
    """近似 MC 按钮底色的渐变（预览用）。"""
    img = Image.new("RGBA", (max(1, int(w * z)), max(1, int(h * z))), (0, 0, 0, 0))
    px = img.load()
    W, H = img.size
    for yy in range(H):
        for xx in range(W):
            t = yy / max(1, H - 1)
            base = int(0x66 if t < 0.5 else 0x66 + (0x8a - 0x66) * (t - 0.5) * 2)
            px[xx, yy] = (base, base, base, 255)
    # 上边缘亮线、下边缘暗线（放大时接近原版按钮的上下边）
    return img


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Minecraft GUI 通用编辑器")
        self.geometry("1280x840")
        self.minsize(1060, 680)
        self._apply_theme()

        # 参数
        self.var_bg = tk.StringVar(value=os.path.join(PROJECT_ROOT, "pic", "bg.png"))
        slot_default = os.path.join(PROJECT_ROOT, "pic", "slot_s.png")
        if not os.path.exists(slot_default):
            slot_default = os.path.join(PROJECT_ROOT, "pic", "slot.png")
        self.var_slot = tk.StringVar(value=slot_default)
        # 槽位素材可能是 17x17(slot.png) 或 18x18(slot_s.png)，统一按真实尺寸取用
        try:
            from PIL import Image as _Img
            _sz = _Img.open(slot_default).size[0]
            MGB.SLOT_PX = int(_sz)
        except Exception:
            MGB.SLOT_PX = 18 if "slot_s" in slot_default else 17
        self.var_out = tk.StringVar(value=DEFAULT_OUT)
        self.var_w = tk.IntVar(value=176)
        self.var_h = tk.IntVar(value=166)
        self.var_grid = tk.IntVar(value=GRID)
        self.var_ox = tk.IntVar(value=0)
        self.var_oy = tk.IntVar(value=0)
        self.var_snap = tk.BooleanVar(value=True)
        self.var_tool = tk.StringVar(value="slot")  # slot | button | text
        self.var_zoomfit = tk.BooleanVar(value=False)

        # 状态
        self.zoom = 1.0
        self.elements = []          # 见 _new_element
        self._img_ids = []          # 每个元素对应一个画布 image/文字 item id
        self.selected = set()       # 选中的元素索引
        self._hover = None          # 当前悬停元素索引（驱动按钮高亮）
        self._drag = None           # ("marquee",...)|("move",...)|("resize",idx,handle,...)|("bgresize",...)
        self._bg_photo = None
        self._bg_raw = None
        self._slot_photo = None
        self._btn_photos = []      # 保持按钮预览图引用（防止 GC 后画布空白）
        self._btn_cache = {}       # (state,w,h,z) -> PIL 图像 缓存
        self._asset_dir = None     # 载入自包含描述时解包出的素材目录（默认用 pic/）
        self._font_cache = {}

        self._build_toolbar()
        left = ttk.Frame(self, padding=6)
        left.pack(side="left", fill="y")
        self._build_controls(left)
        right = ttk.Frame(self)
        right.pack(side="left", fill="both", expand=True)
        self._build_canvas(right)

        self.protocol("WM_DELETE_WINDOW", self.destroy)
        self.rebuild()

    # ---------- 元素模型 ----------
    def _new_element(self, kind, x, y, w=0, h=0, label="", text="", cb="", color="#FFFFFF", gid=""):
        if kind == "slot":
            return {"kind": "slot", "x": x, "y": y, "gid": gid}
        if kind == "button":
            return {"kind": "button", "x": x, "y": y, "w": w or 40, "h": h or 14,
                    "label": label or "", "handler": ""}
        return {"kind": "text", "x": x, "y": y, "w": w or 60, "h": h or 9,
                "text": text or "文本", "color": color, "shadow": True, "align": "left",
                "vertical": False}

    # ---------- 悬浮提示 ----------
    def _bind_hint(self, widget, text):
        """给控件绑定悬浮文本提示（Enter 显示 / Leave 隐藏）。"""
        tip = None

        def _show(_e=None):
            nonlocal tip
            if tip is not None:
                return
            try:
                tip = tk.Toplevel(self)
                tip.overrideredirect(True)
                tip.attributes("-topmost", True)
                lbl = ttk.Label(tip, text=text, padding=6, relief="solid",
                                borderwidth=1, background="#ffffe1", foreground="#333")
                lbl.pack()
                tip.update_idletasks()
                x = widget.winfo_rootx()
                y = widget.winfo_rooty() + widget.winfo_height() + 2
                tip.geometry("+%d+%d" % (x, y))
            except tk.TclError:
                pass

        def _hide(_e=None):
            nonlocal tip
            if tip is not None:
                try:
                    tip.destroy()
                except tk.TclError:
                    pass
                tip = None

        widget.bind("<Enter>", _show)
        widget.bind("<Leave>", _hide)
        widget.bind("<Button-1>", _hide)

    # ---------- 通用取色 ----------
    def _pick_color(self, parent, var_hex):
        """打开取色器；返回是否成功。取色器给出任意 RGB（用于整体基础色），
        行内颜色只能用 §0-9/a-f 十六色（由下方色块按钮直接插入）。"""
        rgb, _hex = colorchooser.askcolor(color=var_hex.get(), parent=parent,
                                          title="选择基础颜色（行内仍用 § 颜色代码）")
        if rgb is not None:
            var_hex.set("#%02X%02X%02X" % tuple(int(c) for c in rgb))
            return True
        return False

    def _mc_swatch_bar(self, parent, on_pick):
        """生成 16 色 MC 颜色块按钮条，点击回调 (code, hex, label)。"""
        bar = ttk.Frame(parent)
        for code, label in MC_CHIPS:
            hexcol = MC_COLORS[code]
            b = tk.Button(bar, text=label, width=3, relief="flat", cursor="hand2",
                          bg=hexcol, activebackground=hexcol,
                          fg="#fff" if code in ("0", "1", "2", "3", "4", "5", "8", "9") else "#000")
            b.configure(command=lambda c=code, h=hexcol, lb=label: on_pick(c, h, lb))
            self._bind_hint(b, "在光标处插入 §%s —— %s 色" % (code, label))
            b.pack(side="left", padx=1, pady=1)
        return bar

    @staticmethod
    def _slot_rect(e):
        return (e["x"], e["y"], e["x"] + MGB.SLOT_PX, e["y"] + MGB.SLOT_PX)

    @staticmethod
    def _box_rect(e):
        return (e["x"], e["y"], e["x"] + e.get("w", MGB.SLOT_PX), e["y"] + e.get("h", MGB.SLOT_PX))

    def _rect_of(self, e):
        return self._slot_rect(e) if e["kind"] == "slot" else self._box_rect(e)

    def _find_at(self, cx, cy):
        for i in range(len(self.elements) - 1, -1, -1):
            x0, y0, x1, y1 = self._rect_of(self.elements[i])
            if x0 <= cx < x1 and y0 <= cy < y1:
                return i
        return None

    def _hit_handles(self, cx, cy):
        """返回 (index, handle) —— 命中某按钮/文本的 8 个调整柄之一。"""
        if len(self.selected) != 1:
            return None
        idx = next(iter(self.selected))
        e = self.elements[idx]
        if e["kind"] == "slot":
            return None
        # 命中半径较大：鼠标靠近角/边即进入“拉伸”，元素内部才是“移动”，两者不再打架
        tol = max(6, HANDLE / self.zoom)
        x0, y0, x1, y1 = self._box_rect(e)
        hs = [
            (0, x0, y0, 1, 1), (1, x1, y0, 0, 1), (2, x0, y1, 1, 0), (3, x1, y1, 0, 0),
            (4, (x0 + x1) / 2, y0, 0, 1), (5, (x0 + x1) / 2, y1, 0, 0),
            (6, x0, (y0 + y1) / 2, 1, 0), (7, x1, (y0 + y1) / 2, 0, 0),
        ]
        for htag, hx, hy, keep_l, keep_t in hs:
            if abs(cx - hx) <= tol and abs(cy - hy) <= tol:
                return (idx, (htag, keep_l, keep_t))
        return None

    # ---------- 现代深色主题 ----------
    def _apply_theme(self):
        self.configure(background="#1c1d23")
        style = ttk.Style(self)
        for th in ("clam", "vista", "alt", "default"):
            try:
                style.theme_use(th)
                break
            except tk.TclError:
                continue
        bg = "#1c1d23"
        panel = "#26272f"
        raised = "#31323c"
        txt = "#e8e9ee"
        muted = "#9aa0ad"
        accent = "#6f7ff2"
        accent_hot = "#8d9aff"
        border = "#3a3c47"
        style.configure(".", background=bg, foreground=txt, font=("Segoe UI", 10))
        style.configure("TFrame", background=bg)
        style.configure("TLabelframe", background=bg, bordercolor=border)
        style.configure("TLabelframe.Label", background=bg, foreground=txt)
        style.configure("TLabel", background=bg, foreground=txt)
        style.configure("TButton", background=panel, foreground=txt,
                        borderwidth=1, relief="flat", padding=(10, 5))
        style.map("TButton",
                  background=[("active", raised), ("pressed", "#20212a"),
                              ("disabled", "#23242b")],
                  foreground=[("disabled", muted)])
        style.configure("Accent.TButton", background=accent, foreground="#ffffff",
                        padding=(12, 5))
        style.map("Accent.TButton", background=[("active", accent_hot), ("pressed", "#5a66d6")])
        style.configure("TEntry", fieldbackground="#15161a", foreground=txt,
                        bordercolor=border, lightcolor=border, darkcolor=border, padding=4,
                        insertcolor=txt)
        style.map("TEntry", bordercolor=[("focus", accent)])
        style.configure("TCombobox", fieldbackground="#15161a", foreground=txt,
                        bordercolor=border, padding=3, arrowcolor=muted)
        style.map("TCombobox",
                  fieldbackground=[("readonly", "#15161a")],
                  selectbackground=[("readonly", "#15161a")])
        style.configure("TCheckbutton", background=bg, foreground=txt)
        style.map("TCheckbutton", background=[("active", bg)])
        style.configure("TRadiobutton", background=bg, foreground=txt)
        style.map("TRadiobutton", background=[("active", bg)])
        style.configure("Vertical.TScrollbar", troughcolor="#14151a",
                        background="#4a4d58", arrowcolor=muted)
        style.configure("Horizontal.TScrollbar", troughcolor="#14151a",
                        background="#4a4d58", arrowcolor=muted)

    # ---------- 界面 ----------
    def _build_toolbar(self):
        bar = ttk.Frame(self, padding=4)
        bar.pack(side="top", fill="x")
        ttk.Button(bar, text="生成 PNG(槽位)", command=self.render_png).pack(side="left")
        ttk.Button(bar, text="生成 Java 控件代码", command=self.gen_code).pack(side="left", padx=(6, 0))
        ttk.Button(bar, text="保存工程", command=self.save_project).pack(side="left", padx=(6, 0))
        ttk.Button(bar, text="载入工程", command=self.load_project).pack(side="left", padx=(4, 0))
        ttk.Button(bar, text="导出项目描述(.md)", command=self.export_desc).pack(side="left", padx=(4, 0))
        ttk.Button(bar, text="清空", command=self.clear_all).pack(side="left", padx=(6, 0))
        ttk.Button(bar, text="✕ 删除选中", command=self.del_selected).pack(side="left", padx=(6, 0))
        self._zoom_label = ttk.Label(bar, text="缩放 100%")
        self._zoom_label.pack(side="left", padx=10)
        tk.Label(bar,
                 text="拖空白=框选 · 拖元素=移动 · 拖柄=拉伸 · Ctrl+滚轮=缩放 · 双击=编辑/新增 · 右键=删",
                 fg="#999", bg=ttk.Style(self).lookup("TFrame", "background")).pack(side="left", padx=6)

    def _build_controls(self, parent):
        r = 0

        def row(label, var, width=14):
            nonlocal r
            ttk.Label(parent, text=label).grid(row=r, column=0, sticky="w", pady=1)
            ttk.Entry(parent, textvariable=var, width=width).grid(
                row=r, column=1, sticky="we", padx=(4, 0), pady=1)
            r += 1

        ttk.Label(parent, text="— 放置工具 —").grid(
            row=r, column=0, columnspan=2, sticky="w", pady=(2, 2)); r += 1
        tools = ttk.Frame(parent)
        tools.grid(row=r, column=0, columnspan=2, sticky="we", pady=(0, 2)); r += 1
        for tkey, ttext in [("slot", "＋槽位"), ("button", "＋按钮"), ("text", "＋文本")]:
            rb = ttk.Radiobutton(tools, text=ttext, value=tkey, variable=self.var_tool)
            rb.pack(side="left", padx=(0, 4))

        ttk.Label(parent, text="— 界面尺寸（拖右下角也可）—").grid(
            row=r, column=0, columnspan=2, sticky="w", pady=(4, 2)); r += 1
        row("宽 px", self.var_w)
        row("高 px", self.var_h)

        ttk.Label(parent, text="— 素材路径 —").grid(
            row=r, column=0, columnspan=2, sticky="w", pady=(6, 2)); r += 1
        row("背景 bg.png", self.var_bg)
        row("槽位 slot.png", self.var_slot)
        row("输出 PNG", self.var_out)

        ttk.Label(parent, text="— 对齐 —").grid(
            row=r, column=0, columnspan=2, sticky="w", pady=(6, 2)); r += 1
        ttk.Checkbutton(parent, text="网格吸附 / 网格线", variable=self.var_snap,
                        command=self.redraw).grid(row=r, column=0, columnspan=2, sticky="w"); r += 1
        row("网格步长 px", self.var_grid)
        row("偏移 X", self.var_ox)
        row("偏移 Y", self.var_oy)

        ttk.Label(parent, text="— 快捷添加 —").grid(
            row=r, column=0, columnspan=2, sticky="w", pady=(6, 2)); r += 1
        for text, fn in [("＋ 玩家背包整组(9×3)", lambda: self.add_block(9, 3, "player_inv")),
                         ("＋ 快捷栏整组(9×1)", lambda: self.add_block(9, 1, "hotbar")),
                         ("＋ 插件列整组(1×6)", lambda: self.add_block(1, 6, "upgrades")),
                         ("＋ 单个槽位", lambda: self.quick_add("slot")),
                         ("＋ 按钮(40×14 空)", lambda: self.quick_add("button")),
                         ("＋ 文本", lambda: self.quick_add("text")),
                         ("编辑选中元素…", self.edit_selected)]:
            ttk.Button(parent, text=text,
                       command=fn).grid(row=r, column=0, columnspan=2, sticky="we", pady=1); r += 1
        hint = tk.Label(parent, text="提示：双击元素=编辑；右键画布=添加/编辑菜单；按钮/文本选中后拖四角四边可拉伸",
                        fg="#888", bg="#eef0f2", wraplength=150, justify="left")
        hint.grid(row=r, column=0, columnspan=2, sticky="w", pady=(6, 0)); r += 1
        self._bind_hint(hint, "整组（玩家背包/快捷栏/插件列）添加后作为一个整体：点其中任意槽位会选中整组并一起拖动；右键该组可“解组”拆成单个槽位。")
        self._count_var = tk.StringVar(value="元素：0  选中：0")
        ttk.Label(parent, textvariable=self._count_var).grid(
            row=r, column=0, columnspan=2, sticky="w", pady=(8, 0))

    def _build_canvas(self, parent):
        wrap = ttk.Frame(parent)
        wrap.pack(fill="both", expand=True)
        self.canvas = tk.Canvas(wrap, bg="#20242a", highlightthickness=0)
        hbar = ttk.Scrollbar(wrap, orient="horizontal", command=self.canvas.xview)
        vbar = ttk.Scrollbar(wrap, orient="vertical", command=self.canvas.yview)
        self.canvas.configure(xscrollcommand=hbar.set, yscrollcommand=vbar.set)
        hbar.pack(side="bottom", fill="x")
        vbar.pack(side="right", fill="y")
        self.canvas.pack(side="left", fill="both", expand=True)

        self.canvas.bind("<Button-1>", self.on_left)
        self.canvas.bind("<B1-Motion>", self.on_drag)
        self.canvas.bind("<ButtonRelease-1>", self.on_release)
        self.canvas.bind("<Button-3>", self.on_right)
        self.canvas.bind("<Double-Button-1>", self.on_double)
        self.canvas.bind("<Control-MouseWheel>", self.on_ctrl_wheel)
        self.canvas.bind("<MouseWheel>", lambda e: self.canvas.yview_scroll(
            -1 if e.delta > 0 else 1, "units"))
        self.canvas.bind("<Motion>", self._on_motion)

    def _on_motion(self, e):
        if self._drag is not None:
            return
        cx, cy = self._to_content(e)
        idx = self._find_at(cx, cy)
        if idx != self._hover:
            self._hover = idx
            self.redraw()
        # 鼠标指针跟随操作语义：控制点上=拉伸光标，元素内部=可拖动光标
        self._apply_cursor(cx, cy, idx)

    def _apply_cursor(self, cx, cy, idx):
        if idx is None:
            self._set_cursor("arrow")
            return
        e = self.elements[idx]
        if e["kind"] == "slot" or e.get("gid"):
            self._set_cursor("fleur")
            return
        x0, y0, x1, y1 = self._box_rect(e)
        band = max(4, 10 / self.zoom)
        left = cx - x0 <= band
        right = x1 - cx <= band
        top = cy - y0 <= band
        bottom = y1 - cy <= band
        if left or right or top or bottom:
            self._apply_resize_cursor(left, right, top, bottom)
        else:
            self._set_cursor("fleur")

    def _set_cursor(self, name):
        try:
            self.canvas.configure(cursor=name)
        except tk.TclError:
            pass

    # ---------- 坐标换算 ----------
    def _to_canvas(self, x, y):
        return MAR + x * self.zoom, MAR + y * self.zoom

    def _to_content(self, e):
        cx = (self.canvas.canvasx(e.x) - MAR) / self.zoom
        cy = (self.canvas.canvasy(e.y) - MAR) / self.zoom
        return cx, cy

    # ---------- 元素操作 ----------
    def add_block(self, cols, rows, gid=""):
        g = self.var_grid.get() if self.var_snap.get() else 0
        ox, oy = self.var_ox.get(), self.var_oy.get()
        w, h = self.var_w.get(), self.var_h.get()
        existing = {tuple(self._slot_rect(e)) for e in self.elements if e["kind"] == "slot"}
        added = []
        for rr in range(rows):
            for cc in range(cols):
                x = _snap(ox + cc * GRID, ox, g)
                y = _snap(oy + rr * GRID, oy, g)
                x = min(max(x, 0), max(0, w - MGB.SLOT_PX))
                y = min(max(y, 0), max(0, h - MGB.SLOT_PX))
                if (x, y, x + MGB.SLOT_PX, y + MGB.SLOT_PX) not in existing:
                    idx = len(self.elements)
                    self.elements.append(self._new_element("slot", x, y, gid=gid))
                    existing.add((x, y, x + MGB.SLOT_PX, y + MGB.SLOT_PX))
                    added.append(idx)
        # 整组作为一个整体选中，便于一次性拖动
        if gid and added:
            self.selected = set(added)
        else:
            self.selected = set(added) if added else self.selected
        self.redraw()

    def quick_add(self, kind):
        g = self.var_grid.get() if self.var_snap.get() else 0
        ox, oy = self.var_ox.get(), self.var_oy.get()
        x = _snap(ox, ox, g)
        y = _snap(oy, oy, g)
        idx = self._append_new(kind, x, y)
        if kind in ("button", "text"):
            # 添加后立刻弹出编辑框填写内容
            self.after(80, self.edit_selected)
        return idx

    def _append_new(self, kind, cx, cy):
        g = self.var_grid.get() if self.var_snap.get() else 0
        ox, oy = self.var_ox.get(), self.var_oy.get()
        x = _snap(cx, ox, g)
        y = _snap(cy, oy, g)
        w, h = self.var_w.get(), self.var_h.get()
        if kind == "button":
            x = min(max(x, 0), max(0, w - 8))
            y = min(max(y, 0), max(0, h - 8))
            e = self._new_element("button", x, y)
        elif kind == "text":
            x = min(max(x, 0), max(0, w - 1))
            y = min(max(y, 0), max(0, h - 8))
            e = self._new_element("text", x, y)
        else:
            x = min(max(x, 0), max(0, w - MGB.SLOT_PX))
            y = min(max(y, 0), max(0, h - MGB.SLOT_PX))
            e = self._new_element("slot", x, y)
        idx = len(self.elements)
        self.elements.append(e)
        self.selected = {idx}
        self.redraw()
        return idx

    def clear_all(self):
        if self.elements and not messagebox.askyesno("清空", "确定清空全部元素？"):
            return
        self.elements.clear()
        self.selected.clear()
        self.redraw()

    # ---------- 鼠标交互 ----------
    def on_left(self, e):
        cx, cy = self._to_content(e)
        # 画布尺寸拉伸柄优先
        w, h = self.var_w.get(), self.var_h.get()
        px, py = self._to_canvas(w, h)
        if px <= self.canvas.canvasx(e.x) <= px + GRIP + 8 and py <= self.canvas.canvasy(e.y) <= py + GRIP + 8:
            self._drag = ("bgresize", self.canvas.canvasx(e.x), self.canvas.canvasy(e.y), w, h)
            return
        # 元素边/角 → 拉伸；元素内部（整组）→ 拖动；空白 → 框选
        hit = self._find_at(cx, cy)
        if hit is not None:
            e0 = self.elements[hit]
            gid = e0.get("gid")
            if e0["kind"] != "slot" and not gid:
                x0, y0, x1, y1 = self._box_rect(e0)
                band = max(4, 10 / self.zoom)
                left = cx - x0 <= band
                right = x1 - cx <= band
                top = cy - y0 <= band
                bottom = y1 - cy <= band
                if left or right or top or bottom:
                    # 按住边/角 = 拉伸（左/右/上/下谁被按住谁动）
                    self.selected = {hit}
                    self._drag = ("resize", hit, left, right, top, bottom,
                                  x0, y0, e0["w"], e0["h"])
                    self._apply_resize_cursor(left, right, top, bottom)
                    self.redraw()
                    return
            # 元素内部 / 整组：拖动
            if gid:
                self.selected = {i for i, e in enumerate(self.elements) if e.get("gid") == gid}
            elif hit not in self.selected:
                self.selected = {hit}
            self._drag = ("move", [(e["x"], e["y"]) for e in self.elements],
                          hit, cx, cy)
            self.redraw()
            return
        self.selected.clear()
        self._drag = ["marquee", cx, cy, cx, cy]
        self._draw_marquee()

    def _apply_resize_cursor(self, left, right, top, bottom):
        h = left or right
        v = top or bottom
        if h and v:
            if left == right:
                self._set_cursor("size_ns")
            elif (left and top) or (right and bottom):
                self._set_cursor("size_nw_se")
            else:
                self._set_cursor("size_ne_sw")
        elif h:
            self._set_cursor("size_we")
        elif v:
            self._set_cursor("size_ns")
        else:
            self._set_cursor("arrow")

    def on_drag(self, e):
        if self._drag is None:
            return
        kind = self._drag[0]
        cx, cy = self._to_content(e)
        if kind == "marquee":
            self._drag[2] = cx
            self._drag[3] = cy
            self._draw_marquee()
            return
        if kind == "bgresize":
            _, sx, sy, w0, h0 = self._drag
            g = self.var_grid.get() if self.var_snap.get() else 0
            nw = max(8, w0 + (self.canvas.canvasx(e.x) - sx) / self.zoom)
            nh = max(8, h0 + (self.canvas.canvasy(e.y) - sy) / self.zoom)
            if g > 0:
                nw = max(8, _snap(nw, 0, g))
                nh = max(8, _snap(nh, 0, g))
            self.var_w.set(int(round(nw)))
            self.var_h.set(int(round(nh)))
            self.rebuild()
            return
        if kind == "move":
            _, originals, _anchor, ax0, ay0 = self._drag
            ctrl = bool(e.state & 0x0004)
            g = self.var_grid.get() if (self.var_snap.get() or ctrl) else 0
            ox, oy = self.var_ox.get(), self.var_oy.get()
            if g > 0:
                dx = _snap(cx, ox, g) - _snap(ax0, ox, g)
                dy = _snap(cy, oy, g) - _snap(ay0, oy, g)
            else:
                dx = int(cx - ax0)
                dy = int(cy - ay0)
            w, h = self.var_w.get(), self.var_h.get()
            for idx in self.selected:
                e = self.elements[idx]
                lx = max(0, w - MGB.SLOT_PX) if e["kind"] == "slot" else max(0, w - 1)
                ly = max(0, h - MGB.SLOT_PX) if e["kind"] == "slot" else max(0, h - 1)
                tx = originals[idx][0] + dx
                ty = originals[idx][1] + dy
                if g > 0:
                    # Ctrl 吸附：元素左上角直接对齐到网格格点（绝对坐标）
                    tx = _snap(tx, ox, g)
                    ty = _snap(ty, oy, g)
                e["x"] = min(max(int(tx), 0), lx)
                e["y"] = min(max(int(ty), 0), ly)
            self.redraw()
            return
        if kind == "resize":
            _, idx, left, right, top, bottom, ox0, oy0, ow0, oh0 = self._drag
            el = self.elements[idx]
            x1o = ox0 + ow0
            y1o = oy0 + oh0
            nx0 = cx if left else ox0
            nx1 = cx if right else x1o
            ny0 = cy if top else oy0
            ny1 = cy if bottom else y1o
            nw = nx1 - nx0
            nh = ny1 - ny0
            ctrl_r = bool(e.state & 0x0004)
            g = self.var_grid.get() if (self.var_snap.get() or ctrl_r) else 0
            if g > 0 and el["kind"] != "text":
                nx0 = _snap(nx0, 0, g)
                nx1 = _snap(nx1, 0, g)
                ny0 = _snap(ny0, 0, g)
                ny1 = _snap(ny1, 0, g)
                nw = nx1 - nx0
                nh = ny1 - ny0
            if nw < 4:
                nw = 4
            if nh < 4:
                nh = 4
            el["x"] = int(nx0)
            el["y"] = int(ny0)
            el["w"] = int(nw)
            el["h"] = int(nh)
            self.redraw()

    def on_release(self, _e):
        if self._drag is not None and self._drag[0] == "marquee":
            _, x0, y0, x1, y1 = self._drag
            if x1 < x0:
                x0, x1 = x1, x0
            if y1 < y0:
                y0, y1 = y1, y0
            self.selected = set()
            for i, e in enumerate(self.elements):
                rx0, ry0, rx1, ry1 = self._rect_of(e)
                if rx1 > x0 and rx0 < x1 and ry1 > y0 and ry0 < y1:
                    self.selected.add(i)
        self._drag = None
        self._set_cursor("arrow")
        self.redraw()

    def on_right(self, e):
        cx, cy = self._to_content(e)
        hit = self._find_at(cx, cy)
        if hit is not None:
            gid = self.elements[hit].get("gid")
            if gid:
                self.selected = {i for i, x in enumerate(self.elements) if x.get("gid") == gid}
            elif hit not in self.selected:
                self.selected = {hit}
        self._open_context_menu(hit, e)

    def _open_context_menu(self, hit, e):
        """右键菜单：空白处添加；元素上编辑/解组/删除。"""
        menu = tk.Menu(self, tearoff=0)

        def add_kind(kind):
            self._append_new(kind, self._to_content(e)[0], self._to_content(e)[1])
            if kind in ("button", "text"):
                self.after(80, self.edit_selected)

        menu.add_command(label="在此添加 槽位", command=lambda: add_kind("slot"))
        menu.add_command(label="在此添加 按钮(空)", command=lambda: add_kind("button"))
        menu.add_command(label="在此添加 文本", command=lambda: add_kind("text"))
        if hit is not None:
            menu.add_separator()
            menu.add_command(label="编辑该元素…", command=self.edit_selected)
            gid = self.elements[hit].get("gid")
            if gid:
                menu.add_command(label="整组编辑…",
                                 command=lambda: self._edit_group_dlg(gid))
                menu.add_command(label="解组（拆成单个）",
                                 command=lambda: self.ungroup(gid))
                menu.add_command(label="删除整组",
                                 command=lambda: self.delete_group(gid))
            menu.add_separator()
            menu.add_command(label="删除选中", command=self.del_selected)
        try:
            menu.tk_popup(e.x_root, e.y_root)
        finally:
            menu.grab_release()

    def ungroup(self, gid):
        for e in self.elements:
            if e.get("gid") == gid:
                e["gid"] = ""
        self.redraw()

    def delete_group(self, gid):
        self.selected = {i for i, e in enumerate(self.elements) if e.get("gid") == gid}
        self.del_selected()

    def _edit_group_dlg(self, gid):
        """整组属性：仅展示成员并允许改名/解组，供整组坐标微调（x/y 偏移整体应用）。"""
        idxs = [i for i, e in enumerate(self.elements) if e.get("gid") == gid]
        if not idxs:
            return
        win = tk.Toplevel(self)
        win.transient(self)
        win.grab_set()
        win.title("整组：%s （%d 个元素）" % (gid or "(未命名)", len(idxs)))
        frm = ttk.Frame(win, padding=10)
        frm.pack(fill="both", expand=True)
        var_dx = tk.IntVar(value=0)
        var_dy = tk.IntVar(value=0)
        ttk.Label(frm, text="整体偏移 X").grid(row=0, column=0, sticky="w")
        ttk.Entry(frm, textvariable=var_dx, width=8).grid(row=0, column=1, padx=6)
        ttk.Label(frm, text="整体偏移 Y").grid(row=1, column=0, sticky="w")
        ttk.Entry(frm, textvariable=var_dy, width=8).grid(row=1, column=1, padx=6)

        def apply():
            dx = var_dx.get()
            dy = var_dy.get()
            for i in idxs:
                self.elements[i]["x"] += dx
                self.elements[i]["y"] += dy
            win.destroy()
            self.redraw()

        def do_ungroup():
            self.ungroup(gid)
            win.destroy()

        btns = ttk.Frame(frm)
        btns.grid(row=3, column=0, columnspan=2, pady=8)
        ttk.Button(btns, text="应用偏移", command=apply).pack(side="left")
        ttk.Button(btns, text="解组", command=do_ungroup).pack(side="left", padx=6)
        ttk.Button(btns, text="关闭", command=win.destroy).pack(side="left", padx=6)

    def on_double(self, e):
        cx, cy = self._to_content(e)
        hit = self._find_at(cx, cy)
        if hit is not None:
            self.selected = {hit}
            self.edit_element(hit)
            self.redraw()
        else:
            self._append_new(self.var_tool.get(), cx, cy)

    def on_ctrl_wheel(self, e):
        factor = ZOOM_STEP if e.delta > 0 else 1.0 / ZOOM_STEP
        new_zoom = min(ZOOM_MAX, max(ZOOM_MIN, self.zoom * factor))
        self.set_zoom(new_zoom)

    def del_selected(self):
        if self.selected:
            for idx in sorted(self.selected, reverse=True):
                self.elements.pop(idx)
            self.selected.clear()
            self.redraw()

    def edit_selected(self):
        if len(self.selected) == 1:
            self.edit_element(next(iter(self.selected)))
        elif self.selected:
            messagebox.showinfo("提示", "请单选一个元素后再编辑。")

    # ---------- 元素编辑对话框 ----------
    def edit_element(self, idx):
        e = self.elements[idx]
        win = tk.Toplevel(self)
        win.transient(self)
        win.grab_set()
        if e["kind"] == "slot":
            win.title("槽位信息")
            win.geometry("320x110")
            ttk.Label(win, text="槽位（精灵坐标）x=%d y=%d，Menu 坐标 x=%d y=%d"
                      % (e["x"], e["y"], e["x"] + 1, e["y"] + 1)).pack(pady=12)
            return
        if e["kind"] == "button":
            self._edit_button_dlg(win, e)
        else:
            self._edit_text_dlg(win, e)

    def _edit_button_dlg(self, win, e):
        win.title("编辑按钮")
        win.geometry("660x400")
        frm = ttk.Frame(win, padding=10)
        frm.pack(fill="both", expand=True)
        var_label = tk.StringVar(value=e.get("label", ""))
        var_w = tk.IntVar(value=e.get("w", 40))
        var_h = tk.IntVar(value=e.get("h", 14))

        def _row(rr, text, widget):
            ttk.Label(frm, text=text).grid(row=rr, column=0, sticky="nw", padx=(0, 8), pady=3)
            widget.grid(row=rr, column=1, sticky="we", pady=3)

        _row(0, "标签文本", ttk.Entry(frm, textvariable=var_label))
        ent_label = None
        want = var_label._name
        for w in frm.winfo_children():
            try:
                if isinstance(w, ttk.Entry) and w.cget("textvariable") == want:
                    ent_label = w
                    break
            except tk.TclError:
                continue
        # 尺寸
        sz = ttk.Frame(frm)
        ttk.Entry(sz, textvariable=var_w, width=6).pack(side="left")
        ttk.Entry(sz, textvariable=var_h, width=6).pack(side="left", padx=6)
        _row(1, "宽 / 高", sz)

        # 格式插入条
        fmt = ttk.Frame(frm)
        for code, name in [("§l", "粗体 B"), ("§o", "斜体 I"), ("§n", "下划线 U"),
                           ("§k", "乱码 K"), ("§r", "重置")]:
            b = ttk.Button(fmt, text=name, width=7,
                           command=lambda c=code: self._insert_entry_at_cursor(ent_label, c))
            self._bind_hint(b, "在标签光标处插入格式代码 %s" % code)
            b.pack(side="left", padx=1)
        _row(2, "插入格式", fmt)

        # 16 色块
        ttk.Label(frm, text="颜色(§代码)").grid(row=3, column=0, sticky="nw", padx=(0, 8), pady=3)
        sw = self._mc_swatch_bar(frm, lambda c, h, lb: self._insert_entry_at_cursor(ent_label, "§" + c))
        sw.grid(row=3, column=1, sticky="w")

        # 通用回调方法名（只生成调用占位，方法体你自己写）
        var_handler = tk.StringVar(value=e.get("handler", ""))
        ent_handler = ttk.Entry(frm, textvariable=var_handler)
        ttk.Label(frm, text="点击回调(方法名)").grid(row=4, column=0, sticky="nw",
                                                    padx=(0, 8), pady=3)
        ent_handler.grid(row=4, column=1, sticky="we", pady=3)
        self._bind_hint(ent_handler,
                        "通用设计：这里只写“按下后调用哪个方法名”，工具仅生成调用占位与 TODO 方法骨架，\n"
                        "不替预制任何功能（如 sendButton/翻页/切页）。留空 = 空按钮（画布显示为禁用态）。")

        def save():
            e["label"] = var_label.get()
            e["w"] = max(8, var_w.get())
            e["h"] = max(8, var_h.get())
            e["handler"] = var_handler.get().strip()
            win.destroy()
            self.redraw()

        btns = ttk.Frame(frm)
        btns.grid(row=6, column=0, columnspan=2, sticky="w", pady=8)
        ttk.Button(btns, text="确定", command=save).pack(side="left")
        ttk.Button(btns, text="取消", command=win.destroy).pack(side="left", padx=6)

    @staticmethod
    def _insert_entry_at_cursor(ent, code):
        if ent is None:
            return
        ent.focus_set()
        try:
            pos = ent.index(tk.INSERT)
        except tk.TclError:
            pos = "end"
        # 直接插入，不清空原有内容
        ent.insert(pos, code)
        try:
            ent.icursor(int(ent.index(pos)) + len(code))
        except (tk.TclError, ValueError):
            pass

    def _edit_text_dlg(self, win, e):
        win.title("编辑文本（MC 富文本：§ 代码）")
        win.geometry("680x470")
        frm = ttk.Frame(win, padding=10)
        frm.pack(fill="both", expand=True)

        row0 = ttk.Frame(frm)
        row0.pack(fill="x", anchor="w")
        ttk.Label(row0, text="文本内容（§6橙§f白 §l粗 §o斜 §n下划线 §k乱码）").pack(side="left")
        self._bind_hint(row0, "写法示例：§6已完成 §f共 §l3 §r步。\n"
                              "§0-9/a-f = 颜色；§l粗体 §o斜体 §n下划线 §k乱码 §r恢复默认。")

        tbox = tk.Text(frm, height=3, width=64, font=("Consolas", 10), wrap="char",
                       undo=True)
        tbox.pack(fill="x", pady=6)
        tbox.insert("1.0", e.get("text", ""))
        tbox.focus_set()

        # 格式按钮
        fmt = ttk.Frame(frm)
        fmt.pack(fill="x", pady=(0, 4))
        for code, name in [("§l", "粗体 B"), ("§o", "斜体 I"), ("§n", "下划线 U"),
                           ("§k", "乱码 K"), ("§r", "重置 R")]:
            b = ttk.Button(fmt, text=name, width=8,
                           command=lambda c=code: tbox.insert(tk.INSERT, c))
            self._bind_hint(b, "在光标处插入 %s" % code)
            b.pack(side="left", padx=1)

        # 16 色块插入
        lbl = ttk.Label(frm, text="行内颜色(点击插入 §代码)：")
        lbl.pack(anchor="w")
        self._bind_hint(lbl, "原版文本内的颜色只能用 § 转义（16 色）。任意 RGB 仅作为未加 § 段的默认基础色，见下方取色器。")
        self._mc_swatch_bar(frm, lambda c, h, lb: tbox.insert(tk.INSERT, "§" + c)).pack(fill="x", pady=(2, 6))

        r2 = ttk.Frame(frm)
        r2.pack(fill="x", pady=3)
        var_color = tk.StringVar(value=e.get("color", "#FFFFFF"))
        var_shadow = tk.BooleanVar(value=e.get("shadow", True))
        var_align = tk.StringVar(value=e.get("align", "left"))
        var_vert = tk.BooleanVar(value=e.get("vertical", False))
        ttk.Label(r2, text="基础颜色").pack(side="left")
        color_lbl = tk.Label(r2, text=var_color.get(), bg=var_color.get(), width=6, relief="solid")
        color_lbl.pack(side="left", padx=4)

        def repick():
            if self._pick_color(frm, var_color):
                color_lbl.configure(text=var_color.get(), bg=var_color.get())

        pk = ttk.Button(r2, text="取色器…", command=repick)
        self._bind_hint(pk, "打开取色器选择该文本元素的默认基础色（任意 RGB）。\n"
                            "注意：文本内部想要变色必须用 § 颜色代码，取色器不会自动转成 §。")
        pk.pack(side="left", padx=4)
        sh = ttk.Checkbutton(r2, text="阴影", variable=var_shadow)
        self._bind_hint(sh, "阴影：文字右下方 1px 绘制深色投影（对应 drawString 的第 5 个参数 true）。\n"
                            "MC 多数界面文字默认开阴影，浅色背景上可关掉。")
        sh.pack(side="left", padx=6)
        ve = ttk.Checkbutton(r2, text="竖排", variable=var_vert)
        self._bind_hint(ve, "竖排：逐字自上而下书写（一列）；取消则为正常横向书写。")
        ve.pack(side="left", padx=6)
        ttk.Label(r2, text="对齐").pack(side="left", padx=(10, 2))
        ttk.Combobox(r2, textvariable=var_align, values=["left", "center", "right"],
                     state="readonly", width=7).pack(side="left")

        def save():
            e["text"] = tbox.get("1.0", "end-1c")
            e["color"] = var_color.get()
            e["shadow"] = var_shadow.get()
            e["align"] = var_align.get()
            e["vertical"] = var_vert.get()
            win.destroy()
            self.redraw()

        btns = ttk.Frame(frm)
        btns.pack(fill="x", pady=8)
        ttk.Button(btns, text="确定", command=save).pack(side="left")
        ttk.Button(btns, text="取消", command=win.destroy).pack(side="left", padx=6)

    # ---------- 渲染 ----------
    def _font(self, size, weight, slant, underline):
        key = (size, weight, slant, underline)
        if key not in self._font_cache:
            f = tkfont.Font(font=_font_spec(size, weight, slant))
            f.configure(underline=underline)
            self._font_cache[key] = f
        return self._font_cache[key]

    def _text_width(self, text, size, weight="normal", slant="roman"):
        return sum(self._font(size, weight, slant, False).measure(t)
                   for t, _c, _w2, _s2, _u in rich_runs(text)) or 0

    def set_zoom(self, zoom):
        self.zoom = zoom
        self._zoom_label.configure(text="缩放 %d%%" % round(zoom * 100))
        self._refresh_slot_photo()
        self._make_zoomed_bg()
        self._apply_scrollregion()
        self.redraw()

    def rebuild(self):
        w, h = self.var_w.get(), self.var_h.get()
        try:
            bg_img = Image.open(self.var_bg.get()).convert("RGBA")
        except Exception:
            bg_img = None
        if bg_img is not None:
            raw = Image.new("RGBA", (w, h), (0, 0, 0, 0))
            MGB.draw_container(raw, bg_img, w, h)
        else:
            raw = Image.new("RGBA", (w, h), (60, 64, 70, 255))
        self._bg_raw = raw
        self._refresh_slot_photo()
        self._make_zoomed_bg()
        self._apply_scrollregion()
        self.redraw()

    def _refresh_slot_photo(self):
        z = self.zoom
        try:
            img = Image.open(self.var_slot.get()).convert("RGBA").crop(
                (0, 0, MGB.SLOT_PX, MGB.SLOT_PX))
            if z != 1.0:
                img = img.resize((max(1, int(MGB.SLOT_PX * z)),
                                  max(1, int(MGB.SLOT_PX * z))), Image.NEAREST)
            self._slot_photo = ImageTk.PhotoImage(img)
        except Exception:
            self._slot_photo = None

    def _make_zoomed_bg(self):
        if self._bg_raw is None:
            self._bg_photo = None
            return
        img = self._bg_raw
        if self.zoom != 1.0:
            img = img.resize((max(1, int(self.var_w.get() * self.zoom)),
                              max(1, int(self.var_h.get() * self.zoom))), Image.NEAREST)
        self._bg_photo = ImageTk.PhotoImage(img)

    def _apply_scrollregion(self):
        w, h = self.var_w.get(), self.var_h.get()
        self.canvas.configure(scrollregion=(0, 0,
                                            MAR * 2 + (w + GRIP + 8) * self.zoom,
                                            MAR * 2 + (h + GRIP + 8) * self.zoom))

    def _draw_marquee(self):
        self.canvas.delete("marquee")
        if self._drag is not None and self._drag[0] == "marquee":
            _, x0, y0, x1, y1 = self._drag
            px0, py0 = self._to_canvas(x0, y0)
            px1, py1 = self._to_canvas(x1, y1)
            self.canvas.create_rectangle(px0, py0, px1, py1,
                                         outline="#ffd76a", dash=(4, 3), width=1,
                                         tags="marquee")

    def redraw(self):
        w, h = self.var_w.get(), self.var_h.get()
        z = self.zoom
        self.canvas.delete("all")
        if self._bg_photo is not None:
            self.canvas.create_image(MAR, MAR, anchor="nw", image=self._bg_photo)
        if self.var_snap.get() and self.var_grid.get() > 0:
            g = self.var_grid.get()
            ox, oy = self.var_ox.get(), self.var_oy.get()
            for gx in range(ox, w + 1, g):
                self.canvas.create_line(MAR + gx * z, MAR, MAR + gx * z, MAR + h * z,
                                        fill="#4a5058", dash=(2, 3))
            for gy in range(oy, h + 1, g):
                self.canvas.create_line(MAR, MAR + gy * z, MAR + w * z, MAR + gy * z,
                                        fill="#4a5058", dash=(2, 3))

        self._img_ids = []
        self._btn_photos = []
        for i, e in enumerate(self.elements):
            px, py = self._to_canvas(e["x"], e["y"])
            if e["kind"] == "slot":
                if self._slot_photo is not None:
                    cid = self.canvas.create_image(px, py, anchor="nw", image=self._slot_photo)
                else:
                    cid = self.canvas.create_rectangle(
                        px, py, px + MGB.SLOT_PX * z, py + MGB.SLOT_PX * z, outline="#9fd0ff")
                self._img_ids.append(cid)
                continue
            if e["kind"] == "button":
                cid = self._draw_button(e)
                self._img_ids.append(cid)
            else:
                cid = self._draw_text(e)
                self._img_ids.append(cid)

        # 选中高亮：亮黄色包络框 + 四角括号，视觉清晰（不随内容混入）
        if self.selected:
            xs = [self._rect_of(self.elements[i]) for i in self.selected]
            bx0 = min(r[0] for r in xs)
            by0 = min(r[1] for r in xs)
            bx1 = max(r[2] for r in xs)
            by1 = max(r[3] for r in xs)
            px0, py0 = self._to_canvas(bx0, by0)
            px1, py1 = self._to_canvas(bx1, by1)
            self.canvas.create_rectangle(px0 - 1, py0 - 1, px1 + 1, py1 + 1,
                                         outline="#ffd23f", width=2)
            # 四角短括号（更好辨认选中的边界）
            b = 10  # 括号臂长（画布像素）
            for cx, cy, dx1, dy1, dx2, dy2 in [
                    (px0, py0, 1, 0, 0, 1), (px1, py0, -1, 0, 0, 1),
                    (px0, py1, 1, 0, 0, -1), (px1, py1, -1, 0, 0, -1)]:
                self.canvas.create_line(cx, cy, cx + dx1 * b, cy, fill="#ffe066", width=2)
                self.canvas.create_line(cx, cy, cx, cy + dy2 * b, fill="#ffe066", width=2)
            # 单选按钮/文本：显示 4 角 + 4 边共 8 个控制点（白色方块、深色描边、足够大）
            if len(self.selected) == 1:
                e = self.elements[next(iter(self.selected))]
                if e["kind"] != "slot":
                    for hx, hy in self._handle_points(e):
                        hxp, hyp = self._to_canvas(hx, hy)
                        half = 6  # 12px 控制点，画布像素固定大小
                        self.canvas.create_rectangle(hxp - half, hyp - half,
                                                     hxp + half, hyp + half,
                                                     fill="#ffffff",
                                                     outline="#101014", width=1)
                        self.canvas.create_line(hxp - half + 2, hyp, hxp + half - 2, hyp,
                                                fill="#101014")
                        self.canvas.create_line(hxp, hyp - half + 2, hxp, hyp + half - 2,
                                                fill="#101014")

        self.canvas.create_rectangle(MAR, MAR, MAR + w * z, MAR + h * z,
                                     outline="#ffffff", width=1)
        gx, gy = MAR + w * z, MAR + h * z
        self.canvas.create_rectangle(gx, gy, gx + GRIP, gy + GRIP, fill="#4a90d9",
                                     outline="#cfe5ff", width=1)
        self.canvas.create_line(gx + 3, gy + GRIP - 2, gx + GRIP - 2, gy + GRIP - 2,
                                gx + GRIP - 2, gy + 3, fill="#ffffff")
        self._draw_marquee()
        self._count_var.set("元素：%d  选中：%d  %dx%d"
                            % (len(self.elements), len(self.selected), w, h))

    @staticmethod
    def _handle_points(e):
        x0, y0, x1, y1 = App._box_rect(e)
        mx, my = (x0 + x1) / 2, (y0 + y1) / 2
        return [(x0, y0), (x1, y0), (x0, y1), (x1, y1),
                (mx, y0), (mx, y1), (x0, my), (x1, my)]

    def _draw_button(self, e):
        px, py = self._to_canvas(e["x"], e["y"])
        w, h = e["w"], e["h"]
        z = self.zoom
        # 默认始终以正常态渲染；悬停时高亮。（禁用贴图仅在元素显式标记 disabled 时使用）
        if e.get("disabled"):
            state = "disabled"
        elif self._hover == self._index_of(e):
            state = "hover"
        else:
            state = "normal"
        key = (state, w, h, z)
        img = self._btn_cache.get(key)
        if img is None:
            base_dir = self._asset_dir if self._asset_dir else WIDGET_DIR
            path = os.path.join(base_dir, BTN_FILES[state])
            img = nine_slice_widget(w, h, z, path)
            if img is None:
                img = _button_bg(w, h, z)
            self._btn_cache[key] = img
        if img is not None:
            photo = ImageTk.PhotoImage(img)
            self._btn_photos.append(photo)
            self.canvas.create_image(px, py, anchor="nw", image=photo, tags="btn_img")
        else:
            self.canvas.create_rectangle(px, py, px + w * z, py + h * z,
                                         outline="#8b8b8b")
        label = e.get("label", "")
        if label:
            self._canvas_draw_text_rich(label, px + (w * z) / 2, py + (h * z) / 2,
                                        base="#FFFFFF", center=True,
                                        max_w=w * z - 6)
        return None

    def _index_of(self, e):
        for i, x in enumerate(self.elements):
            if x is e:
                return i
        return -1

    def _canvas_draw_text_rich(self, text, x, y, base="#FFFFFF", center=False,
                               max_w=None):
        """在画布 (x,y) 处按 rich_runs 逐段绘制，忠实保留 § 颜色/粗/斜/下划线；
        center=True 时整段水平居中（不做整体缩放，MC 也不会自动缩小文字）。"""
        size = max(6, int(round(9 * self.zoom)))
        runs = rich_runs(text, base)
        widths = [self._font(size, w2, s2, False).measure(t)
                  for t, _c, w2, s2, _u in runs]
        total = sum(widths)
        cursor = x - total / 2 if center else x
        cids = []
        for (t, color, weight, slant, underline), wpx in zip(runs, widths):
            fnt = self._font(size, weight, slant, underline)
            cids.append(self.canvas.create_text(cursor, y, anchor="nw", text=t,
                                                fill=color, font=fnt))
            cursor += wpx
        return cids

    def _draw_text(self, e):
        px, py = self._to_canvas(e["x"], e["y"])
        txt = e.get("text", "")
        color = e.get("color", "#FFFFFF")
        align = e.get("align", "left")
        vertical = bool(e.get("vertical", False))
        if not txt:
            return None
        size = max(6, int(round(9 * self.zoom)))
        runs = rich_runs(txt, color)

        def draw_runs(x, y):
            yy = y
            for t, c, weight, slant, underline in runs:
                fnt = self._font(size, weight, slant, underline)
                if vertical:
                    # 竖排：逐字向下
                    step = fnt.metrics("linespace")
                    for ch in t:
                        self.canvas.create_text(x, yy, anchor="nw", text=ch, fill=c, font=fnt)
                        yy += step
                else:
                    self.canvas.create_text(x, yy, anchor="nw", text=t, fill=c, font=fnt)
                    yy += fnt.metrics("linespace")

        if vertical:
            x0 = px
            if align == "center":
                x0 = px + max(0, (e.get("w", 40) * self.zoom - size) / 2)
            elif align == "right":
                x0 = px + e.get("w", 40) * self.zoom - size
            if e.get("shadow", True):
                draw_runs(x0 + 1, py + 1)
            draw_runs(x0, py)
        else:
            total = self._text_width(txt, size)
            x = px
            if align == "center":
                x = px + (e.get("w", 60) * self.zoom - total) / 2
            elif align == "right":
                x = px + e.get("w", 60) * self.zoom - total
            if e.get("shadow", True):
                self._canvas_draw_text_rich(txt, x + 1, py + 1, base="#3f3f3f")
            self._canvas_draw_text_rich(txt, x, py, base=color)
        return None

    # ---------- 输出 ----------
    def render_png(self):
        try:
            w, h = self.var_w.get(), self.var_h.get()
            out = self.var_out.get()
            bg = Image.open(self.var_bg.get()).convert("RGBA")
            slot_img = Image.open(self.var_slot.get()).convert("RGBA").crop(
                (0, 0, MGB.SLOT_PX, MGB.SLOT_PX))
            dst = Image.new("RGBA", (w, h), (0, 0, 0, 0))
            MGB.draw_container(dst, bg, w, h)
            for e in self.elements:
                if e["kind"] != "slot":
                    continue
                x, y = e["x"], e["y"]
                if x + MGB.SLOT_PX <= w and y + MGB.SLOT_PX <= h:
                    dst.paste(slot_img, (x, y))
            os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
            dst.save(out)
            n_slots = sum(1 for e in self.elements if e["kind"] == "slot")
            messagebox.showinfo("完成", "已生成：%s\n(%dx%d, %d 个槽位)" % (out, w, h, n_slots))
        except Exception as exc:
            messagebox.showerror("错误", "生成失败：%s" % exc)

    def gen_code(self):
        lines = ["// ===== 控件布局（make_gui_gui.py 生成）=====",
                 "// 精灵坐标 → Menu/控件坐标 = 精灵 + 1；请在 init() 中 addRenderableWidget",
                 "// leftPos/topPos 已由 AbstractContainerScreen 提供。"]
        slots = [(i, e) for i, e in enumerate(self.elements) if e["kind"] == "slot"]
        buttons = [e for e in self.elements if e["kind"] == "button"]
        texts = [e for e in self.elements if e["kind"] == "text"]
        if slots:
            lines += ["", "// ---- 槽位（菜单 addSlot，同一 IItemHandler，按下标顺序编号）----"]
            for hidx, (_, e) in enumerate(slots):
                gid = e.get("gid", "")
                note = ("  // 组:%s" % gid) if gid else ""
                lines.append(
                    "// slot %2d: addSlot(new SlotItemHandler(HANDLER, %d, %d, %d));%s"
                    % (hidx, hidx, e["x"] + 1, e["y"] + 1, note))
        if buttons:
            lines += ["", "// ---- 按钮（Screen init，回调只占位，方法体自写）----"]
            for i, e in enumerate(buttons):
                label_esc = e.get("label", "").replace("§", "\\u00A7")
                handler = (e.get("handler") or "").strip()
                if handler:
                    lines.append(
                        "// addRenderableWidget(new Button.Builder(Component.literal(\"%s\"), "
                        "btn -> %s()).bounds(leftPos + %d, topPos + %d, %d, %d).build());"
                        % (label_esc, handler, e["x"], e["y"], e["w"], e["h"]))
                    lines.append(
                        "// private void %s() { /* TODO: 在此实现点击行为 */ }" % handler)
                else:
                    lines.append(
                        "// addRenderableWidget(new Button.Builder(Component.literal(\"%s\"), "
                        "btn -> { /* TODO: 补一个点击回调方法 */ }).bounds(leftPos + %d, topPos + %d, %d, %d).build());"
                        % (label_esc, e["x"], e["y"], e["w"], e["h"]))
        if texts:
            lines += ["", "// ---- 文本（renderLabels）----"]
            any_vertical = any(e.get("vertical") for e in texts)
            for e in texts:
                color = e.get("color", "#FFFFFF").lstrip("#")
                try:
                    color_int = "0x" + color
                except Exception:
                    color_int = "0xFFFFFF"
                align = e.get("align", "left")
                shadow = e.get("shadow", True)
                content = e.get("text", "").replace("§", "\\u00A7")
                if e.get("vertical"):
                    # 竖排：逐字向下书写（行距 LINE_H 按需调整）
                    lines.append(
                        "// int _yy = %d; for (String _ln : verticalLines(\"%s\")) "
                        "{ guiGraphics.drawString(font, Component.literal(_ln), %d, _yy, %s, %s); _yy += LINE_H; }"
                        % (e["y"], content, e["x"], color_int,
                           "true" if shadow else "false"))
                else:
                    note = "  // align=%s" % align if align != "left" else ""
                    lines.append(
                        "// guiGraphics.drawString(font, Component.literal(\"%s\"), %d, %d, %s, %s);%s"
                        % (content, e["x"], e["y"], color_int,
                           "true" if shadow else "false", note))
            if any_vertical:
                lines += ["",
                          "// 竖排辅助（加入你的 Screen 类；把每个可见字符拆成一行并延续 § 状态，行距 LINE_H≈8）：",
                          "// private static java.util.List<String> verticalLines(String text) {",
                          "//     java.util.List<String> out = new java.util.ArrayList<>();",
                          "//     String fmt = \"\"; boolean esc = false;",
                          "//     for (int i = 0; i < text.length(); i++) { char ch = text.charAt(i);",
                          "//         if (esc) { fmt += '\\u00A7'; fmt += ch; esc = false; continue; }",
                          "//         if (ch == '\\u00A7') { esc = true; continue; }",
                          "//         out.add(fmt + ch); fmt = \"\"; }",
                          "//     return out; }"]
        lines.append("")
        text = "\n".join(lines)
        win = tk.Toplevel(self)
        win.title("生成的 Java 控件代码")
        win.geometry("860x460")
        box = scrolledtext.ScrolledText(win, wrap="none", font=("Consolas", 10))
        box.pack(fill="both", expand=True, padx=6, pady=6)
        box.insert("1.0", text)
        bar = ttk.Frame(win)
        bar.pack(fill="x", padx=6, pady=(0, 6))
        ttk.Button(bar, text="复制", command=lambda: self._copy(text)).pack(side="left")
        ttk.Button(bar, text="保存为文件",
                   command=lambda: self._save_text(text)).pack(side="left", padx=6)

    # ---------- 工程存取 ----------
    def export_desc(self):
        """导出 Markdown 描述文件：写明界面尺寸、素材与全部元素（种类/位置/尺寸/文字/颜色/格式/分组），
        便于把当前项目状态直接交给 AI 或其他工具继续编辑。"""
        path = filedialog.asksaveasfilename(defaultextension=".md", initialfile="gui_desc.md",
                                            filetypes=[("Markdown", "*.md"), ("Text", "*.txt")])
        if not path:
            return
        lines = []
        lines.append("# GUI 项目描述（make_gui_gui 导出）")
        lines.append("")
        lines.append("- 界面尺寸：%d × %d px" % (self.var_w.get(), self.var_h.get()))
        lines.append("- 背景素材：嵌入文件（assets.bg），不依赖外部路径")
        lines.append("- 槽位素材：嵌入文件（assets.slot，每格 %dpx）；按钮素材 assets.button*/hover/disabled" % MGB.SLOT_PX)
        lines.append("- 网格：步长 %dpx，偏移 (%d, %d)，吸附 %s"
                     % (self.var_grid.get(), self.var_ox.get(), self.var_oy.get(),
                        "开" if self.var_snap.get() else "关"))
        lines.append("- 元素总数：%d" % len(self.elements))
        lines.append("")
        lines.append("## 元素清单")
        lines.append("")
        lines.append("| # | 种类 | 精灵坐标(x,y) | 尺寸(w×h) | 分组 | 文字/内容 | 颜色/格式 | 备注 |")
        lines.append("|---|------|--------------|-----------|------|-----------|-----------|------|")
        for i, e in enumerate(self.elements):
            kind = e["kind"]
            gid = e.get("gid", "")
            x, y = e["x"], e["y"]
            if kind == "slot":
                size = "17×17" if False else "%d×%d" % (MGB.SLOT_PX, MGB.SLOT_PX)
                txtc = "—"
                fmt = "—"
                note = "槽位"
            elif kind == "button":
                size = "%d×%d" % (e.get("w", 40), e.get("h", 14))
                txtc = e.get("label", "") or "（空）"
                fmt = "回调方法: %s" % (e.get("handler", "") or "（占位）")
                note = "按钮"
            else:
                size = "%d×%d" % (e.get("w", 60), e.get("h", 9))
                txtc = e.get("text", "") or "（空）"
                fmt = "基础色 %s；阴影%s；对齐%s；竖排%s" % (
                    e.get("color", "#FFFFFF"),
                    "开" if e.get("shadow", True) else "关",
                    e.get("align", "left"),
                    "是" if e.get("vertical") else "否")
                note = "文本"
            lines.append("| %d | %s | (%d, %d) | %s | %s | `%s` | %s | %s |"
                         % (i, kind, x, y, size, gid or "—",
                            txtc.replace("|", "\\|"), fmt, note))
        lines.append("")
        lines.append("> 坐标说明：精灵坐标 +1 = 游戏中 Menu / 控件坐标（leftPos/topPos 偏移前）。")
        lines.append("> 本文件底部嵌入了完整工程包（含所用素材的 base64）。任何 AI 读到文件末的 ")
        lines.append("> GUI_BUNDLE_BEGIN/END 段即可还原全部状态，无需访问本机任何路径。")
        text = "\n".join(lines)
        try:
            bundle = self._embed_bundle()
            with open(path, "w", encoding="utf-8") as f:
                f.write(text + "\n\n<!-- GUI_BUNDLE_BEGIN -->\n" + bundle +
                        "\n<!-- GUI_BUNDLE_END -->\n")
            messagebox.showinfo("完成", "描述已导出（自包含）：%s\n\n文件内嵌素材与完整工程包，可直接交给他人/AI。" % path)
        except Exception as exc:
            messagebox.showerror("错误", "导出失败：%s" % exc)

    def _embed_bundle(self):
        """构造自包含工程包：全部元素 + 内嵌素材(bg/slot/按钮) 的 base64 JSON。"""
        data = self._project_dict()
        assets = {}
        for key, path in (("bg", self.var_bg.get()), ("slot", self.var_slot.get())):
            if path and os.path.exists(path):
                assets[key] = self._b64_file(path)
        for skey, fname in BTN_FILES.items():
            p = os.path.join(WIDGET_DIR, fname)
            if os.path.exists(p):
                assets["button_" + skey] = self._b64_file(p)
        data["assets"] = assets
        return base64.b64encode(json.dumps(data, ensure_ascii=False).encode("utf-8")).decode("ascii")

    @staticmethod
    def _b64_file(path):
        with open(path, "rb") as f:
            return base64.b64encode(f.read()).decode("ascii")

    def _project_dict(self):
        return {"width": self.var_w.get(), "height": self.var_h.get(),
                "ox": self.var_ox.get(), "oy": self.var_oy.get(),
                "grid": self.var_grid.get(), "bg": self.var_bg.get(),
                "slot": self.var_slot.get(), "elements": self.elements}

    def _apply_project(self, data):
        self.var_w.set(int(data.get("width", 176)))
        self.var_h.set(int(data.get("height", 166)))
        self.var_ox.set(int(data.get("ox", 0)))
        self.var_oy.set(int(data.get("oy", 0)))
        self.var_grid.set(int(data.get("grid", GRID)))
        self.var_bg.set(data.get("bg", self.var_bg.get()))
        self.var_slot.set(data.get("slot", self.var_slot.get()))
        self.elements = data.get("elements", [])
        self.selected.clear()
        self.rebuild()

    def save_project(self):
        path = filedialog.asksaveasfilename(defaultextension=".json", initialfile="gui.json",
                                            filetypes=[("JSON", "*.json")])
        if not path:
            return
        try:
            with open(path, "w", encoding="utf-8") as f:
                json.dump(self._project_dict(), f, ensure_ascii=False, indent=1)
            messagebox.showinfo("完成", "工程已保存：%s" % path)
        except Exception as exc:
            messagebox.showerror("错误", "保存失败：%s" % exc)

    def load_project(self):
        path = filedialog.askopenfilename(
            filetypes=[("项目/描述", "*.json *.md *.txt"), ("JSON", "*.json"),
                       ("Markdown", "*.md")])
        if not path:
            return
        try:
            with open(path, "r", encoding="utf-8") as f:
                raw = f.read()
            data = None
            if "<!-- GUI_BUNDLE_BEGIN -->" in raw:
                beg = raw.index("<!-- GUI_BUNDLE_BEGIN -->") + len("<!-- GUI_BUNDLE_BEGIN -->")
                end = raw.index("<!-- GUI_BUNDLE_END -->")
                payload = base64.b64decode(raw[beg:end].strip()).decode("utf-8")
                data = json.loads(payload)
                assets = data.pop("assets", {})
                self._materialize_assets(assets, data)
            else:
                data = json.loads(raw)
            self._apply_project(data)
            messagebox.showinfo("完成", "工程已载入：%s" % path)
        except Exception as exc:
            messagebox.showerror("错误", "载入失败：%s" % exc)

    def _materialize_assets(self, assets, data):
        """把自包含描述里内嵌的素材解包到本地临时目录并重定向使用路径。"""
        if not assets:
            return
        base = os.path.join(PROJECT_ROOT, "tools", ".bundle")
        os.makedirs(base, exist_ok=True)
        name_map = {"bg": "bg.png",
                    "slot": "slot_s.png",
                    "button_normal": "button.png",
                    "button_hover": "button_highlighted.png",
                    "button_disabled": "button_disabled.png"}
        self._asset_dir = base
        for key, b64 in assets.items():
            fname = name_map.get(key)
            if not fname:
                continue
            target = os.path.join(base, fname)
            with open(target, "wb") as f:
                f.write(base64.b64decode(b64))
        if "bg" in assets:
            data["bg"] = os.path.join(base, "bg.png")
        if "slot" in assets:
            data["slot"] = os.path.join(base, "slot_s.png")

    @staticmethod
    def _copy(text):
        win_clip = tk.Tk()
        win_clip.withdraw()
        win_clip.clipboard_clear()
        win_clip.clipboard_append(text)
        win_clip.update()
        win_clip.destroy()

    @staticmethod
    def _save_text(text):
        path = filedialog.asksaveasfilename(defaultextension=".txt", initialfile="gui_code.txt",
                                            filetypes=[("Text", "*.txt"), ("Java", "*.java")])
        if path:
            with open(path, "w", encoding="utf-8") as f:
                f.write(text)
            messagebox.showinfo("完成", "已保存：%s" % path)


def run(args=None):
    app = App()
    if args is not None:
        app.var_bg.set(args.bg)
        app.var_slot.set(args.slot)
        if getattr(args, "width", None):
            app.var_w.set(args.width)
        if getattr(args, "height", None):
            app.var_h.set(args.height)
        app.rebuild()
    app.mainloop()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Minecraft GUI 背景/槽/按钮/文本通用编辑器")
    parser.add_argument("--bg", default=os.path.join(PROJECT_ROOT, "pic", "bg.png"))
    parser.add_argument("--slot", default=os.path.join(PROJECT_ROOT, "pic", "slot.png"))
    parser.add_argument("--width", type=int, default=176)
    parser.add_argument("--height", type=int, default=166)
    a = parser.parse_args()
    run(a)
