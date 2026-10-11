#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Gradle 缓存根定位 + 同族制品去重（校验/取证脚本共用的唯一判据）。

为什么需要这个模块（两个真实踩过的坑）
--------------------------------------
坑 1「看错缓存 ⇒ 假绿」：
本机 GRADLE_USER_HOME=D:\\gradle ⇒ Gradle 真正读写的是 D:\\gradle\\caches；
但多个校验脚本曾硬编码 C:\\Users\\70432\\.gradle\\caches（另一个缓存根）。
两个缓存根内容并不一致（jar 数量不同、同一坐标的版本不同、甚至 Minecraft 的
parchment 映射都不是同一批），于是「校验通过」证明的是另一个缓存里的制品，
跟 gradle / 游戏实际解析到的那一份不是同一个文件 —— 典型的假绿。
=> 本模块统一**跟随 GRADLE_USER_HOME**，并在输出里显式说明用的是哪个根。

坑 2「同族多版本同时命中 ⇒ 结果不确定」：
同一个缓存根里，同一 group:artifact 可以并存多个版本
（refinedstorage-neoforge 2.0.0 与 2.0.9 就是这样并存下来的）。
任何「全量 glob 拼 classpath / 取 glob[0]」的写法都会把多个版本一起纳入，
最终谁生效取决于目录枚举顺序 ⇒ 编译与校验结果都不可复现。
=> 本模块按 (group, artifact, classifier) 分组，每组只保留一个版本：
   ① gradle.properties 里 pin 的版本优先（RS 用 refinedstorage_version，
      Create 用 create_version，Quartz Arsenal 用 refinedstorageQuartzArsenalVersion）；
   ② 没有 pin 的取**版本号最高**（自然序：逐段比较数字，见 version_sort_key）；
   ③ 被排除的版本全部由调用方显式打印，绝不静默丢弃。

反例自证（无副作用模拟 pin）
--------------------------
设置环境变量 `RSCC_PIN_<属性名大写>=<版本>` 即可临时覆盖 pin，不必改 gradle.properties。
例如 `RSCC_PIN_REFINEDSTORAGE_VERSION=2.0.0` ⇒ 工具会选 2.0.0 并在输出里说明
「来自环境变量覆盖」，用来证明「不会静默串版本」。
"""

from __future__ import annotations

import glob
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


# ---------------------------------------------------------------------------
# 常量
# ---------------------------------------------------------------------------

# 兼容路径：GRADLE_USER_HOME 未设置时，仍旧回到历史上一直用的那个缓存根。
DEFAULT_LEGACY_CACHE = os.path.join(os.path.expanduser("~"), ".gradle", "caches")
# 最后的兜底：本机 gradle.properties 注释里写明的实际缓存根。
FALLBACK_CACHE = r"D:\gradle\caches"

# 坐标 → gradle.properties 里 pin 它的属性名（有 pin 就优先用 pin 的版本）。
PIN_PROPERTIES = {
    "com.refinedmods.refinedstorage:refinedstorage-neoforge": "refinedstorage_version",
    "com.refinedmods.refinedstorage:refinedstorage-quartz-arsenal-neoforge":
        "refinedstorageQuartzArsenalVersion",
    "com.simibubi.create:create-1.21.1": "create_version",
}

# 临时覆盖 pin 的环境变量前缀（见模块 docstring「反例自证」）。
PIN_ENV_PREFIX = "RSCC_PIN_"

# 与 tools/manual_compile.ps1 历史上一直用的排除规则保持一致（只是换成 Python 写法）：
# sources / javadoc 不是可编译制品；natives-windows 是平台本地库；jade 是工程内本地制品
# （libs/jade-*.jar），必须排除缓存里的同名族以免「缓存里躺着一个旧版本」的歧义。
_EXCLUDE_NAME = re.compile(r"sources|javadoc|natives-windows", re.I)
_EXCLUDE_PATH = re.compile(r"parchment|fabric-loader|yarn|sponge-mixin-transformer", re.I)
_JADE_NAME = re.compile(r"^jade-", re.I)

_SOURCE_CLASSIFIERS = ("sources", "javadoc")


# ---------------------------------------------------------------------------
# 工程 / 缓存根
# ---------------------------------------------------------------------------

def project_root():
    """本模块所在仓库的根目录（tools/ 的上一级）。"""
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def cache_root():
    """返回 (缓存根绝对路径, 来源说明)。

    优先级：GRADLE_USER_HOME（Gradle 真正在用的那个）> ~/.gradle/caches（兼容旧行为）
    > D:\\gradle\\caches（兜底）。来源说明会由调用方打印，保证「用了哪个缓存」可自证。
    """
    notes = []
    env = (os.environ.get("GRADLE_USER_HOME") or "").strip()
    if env:
        candidate = os.path.join(env, "caches")
        if os.path.isdir(candidate):
            return candidate, "GRADLE_USER_HOME=%s" % env
        if os.path.isdir(env) and os.path.basename(os.path.normpath(env)).lower() == "caches":
            return env, "GRADLE_USER_HOME=%s（本身即 caches 目录）" % env
        notes.append("[警告] GRADLE_USER_HOME=%s 下找不到 caches 目录 ⇒ 回退默认缓存根" % env)
    if os.path.isdir(DEFAULT_LEGACY_CACHE):
        return DEFAULT_LEGACY_CACHE, "；".join(notes + ["默认 %s（GRADLE_USER_HOME 未设置）"
                                                      % DEFAULT_LEGACY_CACHE])
    if os.path.isdir(FALLBACK_CACHE):
        return FALLBACK_CACHE, "；".join(notes + ["回退 %s" % FALLBACK_CACHE])
    return DEFAULT_LEGACY_CACHE, "；".join(notes + ["默认 %s（当前不存在）" % DEFAULT_LEGACY_CACHE])


def gradle_properties(project=None):
    """读 gradle.properties（Java Properties 的最简子集：key=value，忽略 # 注释行）。"""
    props = {}
    path = os.path.join(project or project_root(), "gradle.properties")
    if not os.path.isfile(path):
        return props
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        for line in handle:
            line = line.strip()
            if not line or line[0] in "#!":
                continue
            key, sep, value = line.partition("=")
            if sep:
                props[key.strip()] = value.strip()
    return props


def pinned_versions(project=None):
    """返回 {"group:artifact": (版本, 来源说明)}；环境变量可临时覆盖（反例自证用）。"""
    props = gradle_properties(project)
    pins = {}
    for coord, prop in PIN_PROPERTIES.items():
        version = props.get(prop)
        source = "gradle.properties:%s" % prop
        override = (os.environ.get(PIN_ENV_PREFIX + prop.upper()) or "").strip()
        if override:
            version = override
            source = "环境变量 %s%s=%s（临时覆盖 gradle.properties:%s）" % (
                PIN_ENV_PREFIX, prop.upper(), override, prop)
        if version:
            pins[coord] = (version, source)
    return pins


# ---------------------------------------------------------------------------
# 版本号比较（自然序）
# ---------------------------------------------------------------------------

def version_sort_key(version):
    """"2.0.9" > "2.0.0"、"9.10.1" > "9.9.1"、"6.0-alpha-3" > "5.0.4" 的排序键。

    做法：把版本串切成「连续数字」与「非数字」交替的段，数字段用 int 比较，
    非数字段小写后按字符串比较。数字段排在非数字段之前（首元素 0 < 1），
    因此 "1.0" 与 "1.0-alpha" 也有确定的大小关系。
    """
    key = []
    for token in re.findall(r"\d+|\D+", str(version)):
        if token.isdigit():
            key.append((0, int(token)))
        else:
            key.append((1, token.lower()))
    return key


def _best_version(versions):
    """版本号最高的那个（同等则按字符串兜底，保证确定）。"""
    return max(versions, key=lambda v: (version_sort_key(v), v))


# ---------------------------------------------------------------------------
# Gradle modules-2 制品索引
# ---------------------------------------------------------------------------

class JarRef:
    """一个缓存里的 jar：<base>\\<group>\\<artifact>\\<version>\\<sha1>\\<file>.jar。"""

    __slots__ = ("path", "group", "artifact", "version", "classifier", "sha1dir")

    def __init__(self, path, group, artifact, version, classifier, sha1dir):
        self.path = path
        self.group = group
        self.artifact = artifact
        self.version = version
        # classifier 为 "" 表示无 classifier；为 None 表示文件名不符合
        # <artifact>-<version>[-<classifier>].jar 的布局 ⇒ 无法判定同族，绝不参与去重。
        self.classifier = classifier
        self.sha1dir = sha1dir

    @property
    def coord(self):
        return "%s:%s" % (self.group, self.artifact)

    def label(self):
        return self.coord + ("[%s]" % self.classifier if self.classifier else "")


def jar_allowed(path):
    """沿用历史规则：哪些缓存 jar 不该进 classpath。"""
    name = os.path.basename(path)
    if _EXCLUDE_NAME.search(name):
        return False
    if _JADE_NAME.match(name):
        return False
    if _EXCLUDE_PATH.search(path):
        return False
    return True


def _split_classifier(file_name, artifact, version):
    """从文件名里剥出 classifier；布局不符返回 None。"""
    if not file_name.lower().endswith(".jar"):
        return None
    stem = file_name[:-4]
    prefix = "%s-%s" % (artifact, version)
    if stem == prefix:
        return ""
    if stem.startswith(prefix + "-"):
        return stem[len(prefix) + 1:]
    return None


def module_jars(root=None, apply_filters=True):
    """枚举 <root>/modules-2/files-2.1 下的 jar（路径排序，顺序确定）。"""
    base = os.path.join(root or cache_root()[0], "modules-2", "files-2.1")
    refs = []
    if not os.path.isdir(base):
        return refs
    for dirpath, _dirnames, filenames in os.walk(base):
        for name in filenames:
            if not name.lower().endswith(".jar"):
                continue
            full = os.path.join(dirpath, name)
            if apply_filters and not jar_allowed(full):
                continue
            parts = os.path.relpath(full, base).split(os.sep)
            if len(parts) < 5:
                # 布局不标准：当成「无法判定同族」处理，原样保留（绝不因为看不出来而丢 jar）。
                refs.append(JarRef(full, None, None, None, None, None))
                continue
            group, artifact, version = parts[0], parts[1], parts[2]
            refs.append(JarRef(full, group, artifact, version,
                               _split_classifier(name, artifact, version), parts[-2]))
    refs.sort(key=lambda r: r.path)
    return refs


class DedupeResult:
    """去重结果：保留了哪些、排除了哪些（含原因）、每个坐标最终选了哪个版本。"""

    def __init__(self):
        self.kept = []        # [JarRef]
        self.excluded = []    # [(JarRef, 原因文本)]
        self.chosen = []      # [JarRef]（每族一个，方便调用方打印版本摘要）
        self.warnings = []    # [str]

    def excluded_lines(self):
        return ["%s  ->  %s" % (ref.path, why) for ref, why in self.excluded]


def dedupe(refs, pins=None):
    """按 (group, artifact, classifier) 去重，每族只留一个版本。

    为什么 key 里必须带 classifier：同一个版本目录下本来就会并存多个**不同制品**
    （例如 lwjgl-3.3.3.jar / lwjgl-3.3.3-natives-windows.jar、
    neoforge-21.1.248-universal.jar / neoforge-21.1.248-userdev.jar）。
    它们是不同 artifact，必须都留在 classpath 里；只有「同 group + 同 artifact +
    同 classifier + 不同 version」才是本任务要消除的多版本歧义。
    """
    pins = pins or {}
    result = DedupeResult()
    buckets = {}
    for ref in refs:
        if ref.group is None or ref.classifier is None:
            result.kept.append(ref)
            continue
        buckets.setdefault((ref.group, ref.artifact, ref.classifier), []).append(ref)

    for (group, artifact, classifier), items in sorted(buckets.items()):
        coord = "%s:%s" % (group, artifact)
        label = coord + ("[%s]" % classifier if classifier else "")
        by_version = {}
        for ref in items:
            by_version.setdefault(ref.version, []).append(ref)

        pin = pins.get(coord)
        if pin and pin[0] in by_version:
            want, reason = pin[0], "pin 命中（%s）" % pin[1]
        else:
            want = _best_version(by_version)
            reason = "版本号最高"
            if pin:
                reason += "；pin %s（%s）在缓存里没有该版本" % (pin[0], pin[1])
                result.warnings.append(
                    "[警告] %s 的 pin 版本 %s（%s）在缓存里找不到 ⇒ 本次退回 %s；"
                    "结果与 gradle.properties 的编译基线不一致，请先跑一次 gradle 依赖解析"
                    % (coord, pin[0], pin[1], want))

        same_version = sorted(by_version[want], key=lambda r: r.path)
        result.kept.append(same_version[0])
        result.chosen.append(same_version[0])
        # 同版本同 classifier 却有多个 sha1 目录（极少见，通常是重复下载）：
        # 按路径排序取第一个，保证结果确定，其余显式列出。
        for ref in same_version[1:]:
            result.excluded.append(
                (ref, "%s：同版本 %s 有 %d 份拷贝，按路径取第一个以保证结果确定"
                 % (label, want, len(same_version))))
        for version in sorted(by_version, key=lambda v: (version_sort_key(v), v)):
            if version == want:
                continue
            for ref in sorted(by_version[version], key=lambda r: r.path):
                result.excluded.append(
                    (ref, "%s：版本 %s 被 %s 顶掉（%s）" % (label, version, want, reason)))

    result.kept.sort(key=lambda r: r.path)
    result.chosen.sort(key=lambda r: r.path)
    result.excluded.sort(key=lambda item: item[0].path)
    return result


# ---------------------------------------------------------------------------
# 按坐标精确挑 jar（校验器专用）
# ---------------------------------------------------------------------------

def find_jar(group, artifact, classifier=None, kind="bin", project=None, root=None):
    """在缓存里找某个坐标的制品，返回 (路径 或 None, [说明行...])。

    kind="bin" 取二进制制品；kind="sources"/"javadoc" 取对应附件。
    classifier=None 表示不限 classifier（同名多个 classifier 时取无 classifier 的那个优先）。
    版本选择与 dedupe 同一套规则：pin 优先，否则版本号最高；退回时**显式告警**。
    """
    notes = []
    if root is None:
        root, source = cache_root()
        notes.append("  Gradle 缓存: %s  [%s]" % (root, source))
    refs = [r for r in module_jars(root, apply_filters=False)
            if r.group == group and r.artifact == artifact and r.classifier is not None]

    if kind in _SOURCE_CLASSIFIERS:
        pool = [r for r in refs if r.classifier == kind]
    else:
        pool = [r for r in refs if r.classifier not in _SOURCE_CLASSIFIERS]
    if classifier is not None:
        narrowed = [r for r in pool if r.classifier == classifier]
        if narrowed:
            pool = narrowed
        else:
            notes.append("[警告] %s:%s 在缓存里没有 classifier=%r 的制品，改用 %s"
                         % (group, artifact, classifier,
                            sorted({r.classifier for r in pool}) or "无"))

    if not pool:
        notes.append("[FATAL] 缓存 %s 里找不到 %s:%s（kind=%s）的制品 jar"
                     % (root, group, artifact, kind))
        return None, notes

    by_version = {}
    for ref in pool:
        by_version.setdefault(ref.version, []).append(ref)

    pin = pinned_versions(project).get("%s:%s" % (group, artifact))
    versions = ",".join(sorted(by_version, key=lambda v: (version_sort_key(v), v)))
    if pin and pin[0] in by_version:
        want, reason = pin[0], "pin 命中（%s）" % pin[1]
    else:
        want = _best_version(by_version)
        reason = "版本号最高"
        if pin:
            reason += "；pin %s（%s）在缓存里没有该版本" % (pin[0], pin[1])
            notes.append("[警告] 缓存里没有 %s 的 pin 版本 %s（%s）⇒ 退回 %s；"
                         "本次结论对不上 gradle.properties 的基线，请先跑一次 gradle 依赖解析"
                         % ("%s:%s" % (group, artifact), pin[0], pin[1], want))
    if len(by_version) > 1:
        notes.append("[提示] %s:%s 在缓存里并存多个版本 [%s] ⇒ 本次使用 %s（%s）"
                     % (group, artifact, versions, want, reason))
    else:
        notes.append("  %s:%s = %s（缓存里只有这一个版本）" % (group, artifact, want))

    picked = sorted(by_version[want], key=lambda r: r.path)[0]
    return picked.path, notes


def find_mc_jar(root=None):
    """Minecraft 的 NeoForge 编译产物（compiledWithNeoForge_*_output.jar），取 mtime 最新。"""
    notes = []
    if root is None:
        root, source = cache_root()
        notes.append("  Gradle 缓存: %s  [%s]" % (root, source))
    pattern = os.path.join(root, "neoformruntime", "intermediate_results",
                           "compiledWithNeoForge_*_output.jar")
    hits = glob.glob(pattern)
    if not hits:
        notes.append("[FATAL] 找不到 Minecraft 编译产物：%s" % pattern)
        return None, notes
    hits.sort(key=lambda p: (os.path.getmtime(p), p))
    if len(hits) > 1:
        notes.append("[提示] MC 编译产物有 %d 份候选（不同 parchment 映射批次）⇒ 取 mtime 最新：%s"
                     % (len(hits), os.path.basename(hits[-1])))
    return hits[-1], notes


def find_vanilla_client_jar(root=None):
    """原版 client jar（读 en_us.json 等最权威来源）。"""
    notes = []
    if root is None:
        root, source = cache_root()
        notes.append("  Gradle 缓存: %s  [%s]" % (root, source))
    pattern = os.path.join(root, "neoformruntime", "artifacts", "minecraft_*_client.jar")
    hits = glob.glob(pattern)
    if not hits:
        notes.append("[警告] 找不到原版 client jar：%s" % pattern)
        return None, notes
    hits.sort(key=lambda p: (os.path.getmtime(p), p))
    return hits[-1], notes


# ---------------------------------------------------------------------------
# 输出辅助
# ---------------------------------------------------------------------------

def cache_banner():
    """给各脚本头部用的「我到底在看哪个缓存」自证行。"""
    root, source = cache_root()
    lines = ["  Gradle 缓存: %s  [%s]" % (root, source)]
    for coord, (version, why) in sorted(pinned_versions().items()):
        lines.append("  pin        : %s = %s  (%s)" % (coord, version, why))
    return lines


def rs_version_summary(result):
    """从去重结果里摘出 Refined Storage 相关制品的实际版本（一眼看出用的哪一版）。"""
    items = []
    for ref in result.chosen:
        if ref.group == "com.refinedmods.refinedstorage":
            items.append("%s=%s" % (ref.artifact, ref.version))
    return ", ".join(sorted(items)) or "<无>"


def excluded_by_artifact(result):
    """被排除的版本按 artifact 汇总（只列坐标与版本，便于一眼核对）。"""
    items = {}
    for ref, _why in result.excluded:
        if ref.group is None:
            items.setdefault("<布局不标准>", set()).add(ref.path)
            continue
        items.setdefault(ref.artifact, set()).add(ref.version)
    return items
