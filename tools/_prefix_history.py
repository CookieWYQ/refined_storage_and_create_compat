# -*- coding: utf-8 -*-
"""「真·修前源码」取法（第 130 轮新增，供若干反例自证复用）。

<h2>为什么需要这个模块</h2>
<p>第 69 / 76 / 80 轮的自检里都有一段「反例自证 · 真源码」：把<b>修前</b>的同一个文件喂给本轮的判据，
必须被判负 —— 用来证明「修前的写法确实会被这条判据抓到」，而不是判据怎么写都绿。</p>
<p>它们原来的取法是 {@code git show HEAD:<file>}，隐含一个假设：<b>HEAD 就是修前</b>。
这在写脚本的那一轮成立（改动还没提交），但一旦把修复提交进 HEAD，取到的就是<b>修后</b>的代码，
反例自证于是恒报「一个都没抓到」——<b>判据本身没坏，取证方式坏了</b>（第 130 轮 commit 后连挂 3 个脚本，
正是这个坑）。</p>

<h2>现在的口径</h2>
<p>在 git 历史里回溯「<b>最近一个仍然满足 predicate</b>」的版本（HEAD 也参与），与 HEAD 现在长什么样无关。
取不到（没有 git / 不在工作树 / 历史上从没满足过）返回 {@code (None, None)}，调用方应退回内置片段或跳过，
并在输出里<b>说清楚出处</b>——判据的判别力不能靠「假装拿到了旧代码」。</p>

用法：
    from _prefix_history import find_last_version
    sha, text = find_last_version(ROOT, "client/tooltip/UnitPatternTooltipComponent.java",
                                  predicate=lambda t: len(visibility_failures(t)) >= 2)
"""

import subprocess

PREFIX = "src/main/java/cretae/cookiewyq/rs_create_compat/"

# 每个文件最多回溯多少个提交（仓库里单文件提交数远小于这个数；上限只是防病态仓库拖慢自检）
MAX_COMMITS = 240


def find_last_version(root, rel_path, marker=None, predicate=None, max_commits=MAX_COMMITS):
    """回溯 git 历史，返回「最近一个仍满足条件」的版本 {@code (sha8, text)}；找不到 ⇒ {@code (None, None)}。

    :param root: 仓库根目录
    :param rel_path: 相对 {@code src/main/java/cretae/cookiewyq/rs_create_compat/} 的路径
    :param marker: 便捷判据 —— 文本里含这个字符串即算命中（与 predicate 二选一，predicate 优先）
    :param predicate: 更精确的判据（接收源码文本，返回 bool）；用于「方法缺某判据」这类结构判定
    """
    path = PREFIX + rel_path
    try:
        log = subprocess.run(["git", "log", "--format=%H", "-n", str(max_commits), "--", path],
                             cwd=root, capture_output=True, text=True,
                             encoding="utf-8", errors="replace")
    except Exception:
        return None, None
    if log.returncode != 0:
        return None, None
    for sha in (log.stdout or "").split():
        try:
            show = subprocess.run(["git", "show", "%s:%s" % (sha, path)], cwd=root,
                                  capture_output=True, text=True,
                                  encoding="utf-8", errors="replace")
        except Exception:
            return None, None
        if show.returncode != 0 or not show.stdout:
            continue
        text = show.stdout
        try:
            hit = predicate(text) if predicate is not None else (marker is None or marker in text)
        except Exception:
            hit = False
        if hit:
            return sha[:8], text
    return None, None
