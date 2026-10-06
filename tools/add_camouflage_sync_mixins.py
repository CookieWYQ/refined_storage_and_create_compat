# -*- coding: utf-8 -*-
"""把「伪装随区块数据包同步」的两个 Mixin 登记进 rs_create_compat.mixins.json。

为什么需要它们（2026-09-30 取证结论，详见两个 Mixin 的类注释与
`support/RsccCamouflageAttachment#writeIntoUpdateTag`）：

  * `blockentity.BlockEntityUpdateTagMixin`
      把伪装并进 `BlockEntity#getUpdateTag` 的返回值 → 搭**区块数据包**的顺风车。
      修「物理化的一瞬间外壳消失」：Sable 自己发 `ClientboundLevelChunkWithLightPacket`
      （`SubLevelTrackingSystem#sendFullSync`），绕开原版 `ChunkMap`，
      NeoForge 的附件同步（唯一触发点 = `ChunkWatchEvent.Sent`）永不执行。
  * `level.ServerLevelBlockUpdateMixin`
      方块更新广播之后补发一次附件 + 重烘信号。修「拆解回来时外壳不恢复」：
      客户端那一格的方块实体此刻是新建的空对象。

脚本是**幂等**的：已经登记过的条目不会再插一遍；写回前用 json.load 校验。
用法: python tools/add_camouflage_sync_mixins.py
"""
import io
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MIXINS = os.path.join(ROOT, "src", "main", "resources", "rs_create_compat.mixins.json")

WANTED = [
    "blockentity.BlockEntityUpdateTagMixin",
    "level.ServerLevelBlockUpdateMixin",
]


def main():
    with io.open(MIXINS, "r", encoding="utf-8") as handle:
        data = json.load(handle)
    mixins = data.get("mixins")
    if not isinstance(mixins, list):
        raise SystemExit("mixins 字段不是数组：%r" % (mixins,))

    added = [name for name in WANTED if name not in mixins]
    if not added:
        print("已登记，无需改动：%s" % ", ".join(WANTED))
        return 0

    # 保持原有的相对位置习惯：紧跟在 block.CamouflageRemovalMixin 之后（伪装相关的一组）
    anchor = "block.CamouflageRemovalMixin"
    insert_at = mixins.index(anchor) + 1 if anchor in mixins else len(mixins)
    for offset, name in enumerate(added):
        mixins.insert(insert_at + offset, name)

    with io.open(MIXINS, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(data, handle, indent=2, ensure_ascii=False)
        handle.write("\n")

    # 写回后立刻校验一遍，避免手滑写出坏 JSON
    with io.open(MIXINS, "r", encoding="utf-8") as handle:
        reloaded = json.load(handle)
    for name in WANTED:
        if name not in reloaded["mixins"]:
            raise SystemExit("写回后校验失败：缺少 %s" % name)
    print("已登记 %d 项：%s" % (len(added), ", ".join(added)))
    print("mixins 现有 %d 项" % len(reloaded["mixins"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
