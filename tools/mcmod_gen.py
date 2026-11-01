# -*- coding: utf-8 -*-
"""MC百科正文生成器 + 格式校验器。

输入：mcmod/inventory.csv + mcmod/entries/<slug>/body.md
输出：mcmod/html/<slug>.fragment.html（粘进「源代码编辑器」的片段）
      mcmod/html/<slug>.page.html（离线预览）
      mcmod/html/index.html（总目录）
      mcmod/html/validation.md（校验报告）

用法：python tools/mcmod_gen.py [--check-only]
"""
import csv, html, re, sys, os
from pathlib import Path

ROOT = Path(r"D:\MODS\refined_storage_and_create_compat")
SKILL = ROOT / "_skills" / "mcmod-page" / "scripts"
sys.path.insert(0, str(SKILL))
from md2mcmod import convert  # noqa: E402

INV = ROOT / "mcmod" / "inventory.csv"
ENTRIES = ROOT / "mcmod" / "entries"
OUT = ROOT / "mcmod" / "html"

# 正文长度上界（超过才允许用 [hN] 分节）
H_HEADING_MIN = 1200

# 由 main() 填充：本模组全部已知百科资料 id（用于校验站内链接）
VALID_IDS = set()

PAGE_CSS = """
body{font-family:"Microsoft YaHei","PingFang SC",sans-serif;max-width:900px;margin:24px auto;padding:0 16px;line-height:1.75;color:#222}
table{border-collapse:collapse;margin:12px 0;width:100%}
th,td{border:1px solid #b8b8b8;padding:6px 8px;vertical-align:top;text-align:left}
th{background:#f2f2f2}
.meta{border:1px solid #ddd;background:#fafafa;padding:12px 16px;margin-bottom:20px;font-size:14px}
.meta b{display:inline-block;width:96px}
h2{font-size:20px;border-left:4px solid #666;padding-left:8px}
h1{font-size:22px}
.note{color:#888;font-size:12px}
"""


def load_inventory():
    rows = list(csv.DictReader(open(INV, encoding="utf-8-sig")))
    return rows


def md_body(path: Path) -> str:
    text = path.read_text(encoding="utf-8")
    # 去掉可能残留的 HTML 注释头
    text = re.sub(r"^<!--.*?-->\s*", "", text, flags=re.S)
    return text.strip() + "\n"


def to_fragment(md: str) -> str:
    # 名称列由写手在 body.md 里用 ** 加粗，避免生成 <strong><strong>…</strong></strong>
    frag = convert(md, color_code=False, bold_first_col=False)
    # 统一「数字 + 百分号」写法：90 % -> 90%
    frag = re.sub(r"(?<=\d)\s+%", "%", frag)
    # 清掉异常空白
    frag = re.sub(r"[ \t]+</p>", "</p>", frag)
    return frag


def preview_page(row, frag: str) -> str:
    # [hN=标题] 预览时转成真标题
    body = re.sub(r"\[h([1-6])=([^\]]*)\]", lambda m: f"<h{m.group(1)}>{html.escape(m.group(2))}</h{m.group(1)}>", frag)
    meta = "".join(f"<div><b>{k}</b>{html.escape(str(v))}</div>" for k, v in [
        ("页面 id", row["page_id"] or "（新增资料，建好后回填）"),
        ("名称", row["name"]), ("英文名", row["ename"]), ("注册名", row["regname"]),
        ("类型", "方块" if row["kind"] == "block" else "物品"),
        ("资料分类", row["category"]), ("版本", row["version"]), ("状态", row["status"]),
    ])
    return f"""<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">
<title>{html.escape(row['name'])} - MC百科正文预览</title><style>{PAGE_CSS}</style></head><body>
<h1>{html.escape(row['name'])} <small class="note">{html.escape(row['ename'])}</small></h1>
<div class="meta">{meta}</div>
<div class="content">{body}</div>
<p class="note">本页是离线预览；实际粘贴请用同名 .fragment.html，并在编辑页勾选「源代码编辑器」。</p>
</body></html>"""


def gen_tutorials(report: list, only_check: bool) -> int:
    """教程：去掉首行 H1（标题由教程页自己填），其余转 HTML。"""
    tut_dir = ROOT / "mcmod" / "tutorial"
    ok = 0
    for md_path in sorted(tut_dir.glob("*.md")):
        md = md_path.read_text(encoding="utf-8")
        lines = md.splitlines()
        title = lines[0].lstrip("# ").strip() if lines else md_path.stem
        body_md = "\n".join(lines[1:]).strip() + "\n"
        frag = to_fragment(body_md)
        issues = []
        if "https://" in frag:
            for href in re.findall(r'<a href="([^"]+)"', frag):
                if not (href.startswith("https://www.mcmod.cn/") or href.startswith("https://github.com/")):
                    issues.append(f"站外链接：{href}")
        if re.search(r"第\s*\d+\s*步", frag) is None:
            issues.append("教程缺少编号步骤")
        slug = md_path.stem
        if not only_check:
            (OUT / f"tutorial-{slug}.fragment.html").write_text(frag, encoding="utf-8")
            body = re.sub(r"\[h([1-6])=([^\]]*)\]", lambda m: f"<h{m.group(1)}>{html.escape(m.group(2))}</h{m.group(1)}>", frag)
            (OUT / f"tutorial-{slug}.page.html").write_text(
                f'<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8"><title>{html.escape(title)}</title>'
                f'<style>{PAGE_CSS}</style></head><body><h1>{html.escape(title)}</h1>{body}</body></html>',
                encoding="utf-8")
        report.append(f"- {'✅' if not issues else '❌'} 教程《{title}》：{len(re.sub(r'<[^>]+>', '', frag))} 字"
                      + ("；问题：" + "；".join(issues) if issues else ""))
        if not issues:
            ok += 1
    return ok


FORBIDDEN = [
    ("<code>", "规范不支持行内代码"),
    ("<blockquote", "规范不支持引用块"),
    ("firstRow", "表头必须用 <th>"),
    ("---", "禁止分割线"),
    ("***", "禁止分割线"),
]
EMOJI = re.compile("[\U0001F300-\U0001FAFF\u2600-\u27BF\uFE0F]")
BAD_WORDS = ["我觉得", "个人认为", "小编", "笔者", "貌似", "据说", "好像", "新版", "旧版", "老版本",
             "欢迎", "未完待续", "PS.", "PSS.", "注：", "！", "。。", "？？"]
# 已知译名错误（应以 lang/zh_cn.json 为准）：命中即改
TERM_FIX = [("石英富集铁", "富石英铁"), ("石英富铁", "富石英铁"),
            ("改良处理器", "进阶处理器"), ("构造核心", "成型核心"),
            ("精密机构", "精密构件")]
BAD_PUNCT = re.compile(r"[，。]{2,}|[；。]{2,}|[ \t]+。")


def check(row, frag: str):
    issues = []
    plain = re.sub(r"<[^>]+>", "", frag)
    if not plain.strip():
        issues.append("正文为空")
    for pat, why in FORBIDDEN:
        if pat in frag:
            issues.append(f"出现 `{pat}`：{why}")
    if "<th>" not in frag and "<table>" in frag.replace("<table", "<table>"):
        issues.append("有表格但没有 <th> 表头")
    m = EMOJI.search(plain)
    if m:
        issues.append(f"含 emoji/符号：{m.group(0)!r}")
    for w in BAD_WORDS:
        if w in plain:
            issues.append(f"含禁用词：{w}")
    if BAD_PUNCT.search(plain):
        issues.append("标点堆叠/多余空格")
    if "**" in plain or "`" in plain:
        issues.append("Markdown 标记残留（** 或 反引号）")
    for wrong, right in TERM_FIX:
        if wrong in plain:
            issues.append(f"译名错误：{wrong} → 应写 {right}")
    if re.search(r"（L\d+）|文件:行号", plain):
        issues.append("残留行号备注")
    # 句末标点：正文段落（不含表格单元格与 [hN=] 标题行）应以标点结尾；表格里的纯名词格可例外
    no_tables = re.sub(r"<table>.*?</table>", "", frag, flags=re.S)
    for para in re.findall(r"<p>(.*?)</p>", no_tables, re.S):
        t = re.sub(r"<[^>]+>", "", para).strip()
        if t.startswith("[h") and "=" in t:
            continue
        if len(t) >= 8 and t[-1] not in "。！？：；」）…":
            issues.append(f"句末缺标点：{t[:24]}…")
    # 标题规范：短资料页（<600 字且无表格）用 [hN] 才提示；长页/带大表允许分节
    heads = re.findall(r"\[h([1-6])=", frag)
    if heads and len(plain) < 600 and "<table>" not in frag:
        issues.append(f"正文仅 {len(plain)} 字却用了 [hN] 标题，短资料页应改用加粗小标题")
    # 链接：必须是 mcmod 站内或作者官方
    for href in re.findall(r'<a href="([^"]+)"', frag):
        if not (href.startswith("https://www.mcmod.cn/") or href.startswith("https://github.com/")):
            issues.append(f"站外链接：{href}")
    # 站内链接必须指向本模组已知资料 id
    for iid in re.findall(r"https://www\.mcmod\.cn/item/(\d+)\.html", frag):
        if iid not in VALID_IDS:
            issues.append(f"链接指向未知资料 id：{iid}")
    # 新增资料必须标版本
    if not row["page_id"] and "（1.2.0 起）" not in plain:
        issues.append("新增资料首段缺少「（1.2.0 起）」版本标注")
    return issues


def main():
    only_check = "--check-only" in sys.argv
    rows = load_inventory()
    VALID_IDS.update(r["page_id"] for r in rows if r["page_id"])
    OUT.mkdir(parents=True, exist_ok=True)
    report = ["# 正文生成与校验报告", ""]
    report.append(f"- 清单条数：{len(rows)}")
    ok = 0
    checked = 0
    index_rows = []
    for row in rows:
        if not row["body"]:
            continue
        body_path = ROOT / "mcmod" / row["body"]
        if not body_path.exists():
            report.append(f"- ❌ {row['name']}：缺少正文 {row['body']}")
            continue
        md = md_body(body_path)
        frag = to_fragment(md)
        slug = body_path.parent.name
        issues = check(row, frag)
        checked += 1
        plain_len = len(re.sub(r"<[^>]+>", "", frag))
        if not issues:
            ok += 1
        status = "✅" if not issues else "❌"
        report.append(f"- {status} {row['name']}（{slug}）：{plain_len} 字" + ("；问题：" + "；".join(issues) if issues else ""))
        if not only_check:
            (OUT / f"{slug}.fragment.html").write_text(frag, encoding="utf-8")
            (OUT / f"{slug}.page.html").write_text(preview_page(row, frag), encoding="utf-8")
        index_rows.append((row, slug, plain_len, issues))
    tut_total = len(list((ROOT / "mcmod" / "tutorial").glob("*.md")))
    tut_ok = gen_tutorials(report, only_check)
    if not only_check:
        tut_html = "".join(
            f'<li><a href="tutorial-{p.stem}.page.html">{html.escape(p.stem)}</a></li>'
            for p in sorted((ROOT / "mcmod" / "tutorial").glob("*.md")))
        rows_html = "\n".join(
            f'<tr><td><a href="{slug}.page.html">{html.escape(r["name"])}</a></td>'
            f'<td>{html.escape(r["ename"])}</td><td>{r["page_id"] or "新增"}</td>'
            f'<td>{html.escape(r["category"])}</td><td>{n}</td><td>{"✅" if not s else "❌ 待改"}</td></tr>'
            for r, slug, n, s in index_rows)
        (OUT / "index.html").write_text(
            f'<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8"><title>MC百科资料正文总览</title>'
            f'<style>{PAGE_CSS}</style></head><body><h1>MC百科资料正文总览（class 31664）</h1>'
            f'<table><tr><th>名称</th><th>英文名</th><th>页面 id</th><th>资料分类</th><th>正文字数</th><th>状态</th></tr>'
            f'{rows_html}</table><h2>教程</h2><ul>{tut_html}</ul></body></html>', encoding="utf-8")
        report_path = OUT / "validation.md"
        report_path.write_text(
            "\n".join(report) + f"\n\n合计：{checked} 条有正文，{ok} 条通过自动校验，{checked-ok} 条待改；"
            f"教程 {tut_total} 篇，{tut_ok} 篇通过。\n", encoding="utf-8")
    print("\n".join(report[:3]))
    print(f"checked={checked} ok={ok} fail={checked-ok}; tutorial {tut_ok}/{tut_total}")
    print("report:", OUT / "validation.md")
    return 0 if (ok == checked and tut_ok == tut_total) else 1


if __name__ == "__main__":
    raise SystemExit(main())
