# -*- coding: utf-8 -*-
# 统一方块硬度/挖掘：复制 RS BlockConstants（strength 2/6, stone sound, 无工具等级要求），
# 并给所有本模组方块补 pickaxe 可挖掘标签（无 needs_* 等级标签 -> 任意稿子都能挖，只是速度不同）。
import io
import json
import os
import re
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


JAVA = r"d:\MODS\refined_storage_and_create_compat\src\main\java\cretae\cookiewyq\rs_create_compat\RS_Create_Compat.java"
TAG_PATH = r"d:\MODS\refined_storage_and_create_compat\src\main\resources\data\minecraft\tags\block\mineable\pickaxe.json"

with io.open(JAVA, "r", encoding="utf-8") as f:
    src = f.read()

# 匹配形如：BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK).strength(3.5F)
pat = re.compile(
    r"BlockBehaviour\.Properties\.ofFullCopy\(Blocks\.IRON_BLOCK\)\.strength\((\d+(?:\.\d+)?)F\)"
)
new_props = (
    "BlockBehaviour.Properties.of().strength(2.0F, 6.0F)"
    ".sound(net.minecraft.world.level.block.SoundType.STONE)"
)
replaced = pat.sub(new_props, src)
count = len(pat.findall(src))

# 收集本模组所有方块注册 id
ids = sorted(set(re.findall(r'BLOCKS\.register\("([a-z0-9_]+)"', replaced)))

tag = {"replace": False, "values": ["rs_create_compat:" + i for i in ids]}

with io.open(JAVA, "w", encoding="utf-8", newline="\n") as f:
    f.write(replaced)

os.makedirs(os.path.dirname(TAG_PATH), exist_ok=True)
with io.open(TAG_PATH, "w", encoding="utf-8", newline="\n") as f:
    json.dump(tag, f, ensure_ascii=False, indent=2)
    f.write("\n")

print("replaced props:", count)
print("tag ids:", ids)
