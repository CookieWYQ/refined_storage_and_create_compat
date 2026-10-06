# -*- coding: utf-8 -*-
"""第 11 轮「全量文本自检」：模组元数据里的说明文字（mod list 可见的那几处）。

只改「玩家/整合包作者能看到」的文本，不动任何版本号、依赖类型、结构：
  * `gradle.properties` 的 `mod_description`（经 templates 的 ${mod_description} 进 mods.toml）；
  * `src/main/templates/META-INF/neoforge.mods.toml` 里两个**可选联动**依赖的 `reason`
    （去掉「没装它就只能…」这类「装了才能」腔调，只留一句功能说明）。

用法：python tools/patch_mod_meta_text.py     （幂等：找不到旧串会直接失败，不会写坏文件）
"""
import io
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROPS = os.path.join(ROOT, "gradle.properties")
TOML = os.path.join(ROOT, "src", "main", "templates", "META-INF", "neoforge.mods.toml")

OLD_DESCRIPTION = (
    "mod_description=A batch of useful blocks and items for Refined Storage & Create compatibility. "
    "Optional link mod: FTB Ultimine - hold its key while right-clicking with a Separation/Camouflage "
    "Frame to frame a whole cable/pipe run at once."
)
NEW_DESCRIPTION = (
    "mod_description=Adds bridges between Refined Storage and Create: sequence assembly autocrafting, "
    "storage and logistics machines."
)

EDITS = (
    # (文件, 旧串, 新串, 说明)
    (PROPS, OLD_DESCRIPTION, NEW_DESCRIPTION, "mod_description：删掉 FTB Ultimine 的联动广告，只留功能"),
    (TOML,
     'reason = "Optional integration (link mod): hold the FTB Ultimine key to frame a whole '
     'same-family cable/pipe run at once; without it every frame gesture stays single-cell."',
     'reason = "Optional integration (link mod): hold the FTB Ultimine key to frame a whole '
     'same-family cable/pipe run at once."',
     "ftbultimine reason：删掉「没装它就只能单格」的腔调"),
    (TOML,
     'reason = "Optional integration: shows the filled block\'s icon and \'<original part> '
     '(camouflaged)\' as the aimed-at block\'s name; without Jade the mod loads and works exactly '
     'the same."',
     'reason = "Optional integration: shows the camouflaged block\'s real name and icon when '
     'aimed at."',
     "jade reason：同上，只留一句功能说明"),
)


def main():
    failures = 0
    for path, old, new, why in EDITS:
        with io.open(path, encoding="utf-8", newline="") as handle:
            text = handle.read()
        if new in text and old not in text:
            print("[skip] 已应用：%s（%s）" % (why, os.path.basename(path)))
            continue
        if old not in text:
            print("[FAIL] 找不到待替换的旧串：%s（%s）" % (why, os.path.relpath(path, ROOT)))
            failures += 1
            continue
        with io.open(path, "w", encoding="utf-8", newline="") as handle:
            handle.write(text.replace(old, new, 1))
        print("[ok] %s（%s）" % (why, os.path.relpath(path, ROOT)))
    if failures:
        return 1
    # 复核：描述必须是 ASCII（gradle.properties 按 ISO-8859-1 读，非 ASCII 必须转义）
    with io.open(PROPS, encoding="latin-1") as handle:
        for line in handle:
            if line.startswith("mod_description="):
                value = line.split("=", 1)[1].strip()
                assert value.isascii(), "mod_description 含非 ASCII 字符，需要 \\uXXXX 转义"
                print("mod_description -> %s" % value)
    return 0


if __name__ == "__main__":
    sys.exit(main())
