# -*- coding: utf-8 -*-
"""第 138 轮 A：把剩余 isLoaded + 阻塞读 收敛到 SafeChunkRead（带断言，失败即停）。"""
import io, sys, os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")

IMPORT_LINE = "import cretae.cookiewyq.rs_create_compat.support.SafeChunkRead;"

# (相对文件, [(old, new, 期望次数)], 是否补 import)
EDITS = []

def add(path, pairs, add_import):
    EDITS.append((path, pairs, add_import))

add("support/RsccWireLinkSearch.java", [
 ("""            final BlockPos neighbor = origin.relative(direction);
            if (!level.isLoaded(neighbor)) {
                continue;
            }
            // 被扳手断开的接缝 / 面：这一侧算不通（与网络图、连接臂外形同一判定）
            if (RsccCableCuts.isDisconnected(level, origin, direction)) {
                continue;
            }
            // 被分隔框架套住且这一侧不在其快照里：这一步不通（同一判定，见 SeparationFrameGuard）
            if (SeparationFrameGuard.blocksConnection(level, origin, direction)) {
                continue;
            }
            final BlockState state = level.getBlockState(neighbor);""",
  """            final BlockPos neighbor = origin.relative(direction);
            // 被扳手断开的接缝 / 面：这一侧算不通（与网络图、连接臂外形同一判定）
            if (RsccCableCuts.isDisconnected(level, origin, direction)) {
                continue;
            }
            // 被分隔框架套住且这一侧不在其快照里：这一步不通（同一判定，见 SeparationFrameGuard）
            if (SeparationFrameGuard.blocksConnection(level, origin, direction)) {
                continue;
            }
            // 第 138 轮：安全读（只读已 FULL 区块）—— 不加载、不阻塞
            final BlockState state = SafeChunkRead.blockStateNow(level, neighbor);
            if (state == null) {
                continue;
            }""", 1),
 ("""                    if (!level.isLoaded(neighbor)) {
                        continue;
                    }
                    final BlockState state = level.getBlockState(neighbor);""",
  """                    // 第 138 轮：安全读（只读已 FULL 区块）—— 不加载、不阻塞
                    final BlockState state = SafeChunkRead.blockStateNow(level, neighbor);
                    if (state == null) {
                        continue;
                    }""", 1),
], False)

add("support/RsccChamberImportStrategy.java", [
 ("""        if (!level.isLoaded(pos)) {
            return null;
        }
        final BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            return null;
        }""",
  """        // 第 138 轮：安全读（只读已 FULL 区块，不加载、不阻塞）
        final BlockEntity blockEntity = SafeChunkRead.blockEntityNow(level, pos);
        if (blockEntity == null) {
            return null;
        }""", 2),
], False)

add("support/RsccSheathChain.java", [
 ("""            if (level == null || pos == null || !level.isLoaded(pos)) {
                return null;
            }
            final BlockState state = level.getBlockState(pos);""",
  """            // 第 138 轮：安全读（只读已 FULL 区块）
            final BlockState state = SafeChunkRead.blockStateNow(level, pos);
            if (state == null) {
                return null;
            }""", 1),
], False)

add("support/RsccCamouflage.java", [
 ("""        if (level instanceof final Level realLevel && !realLevel.isLoaded(pos)) {
            return null; // 未加载：绝不为了取一份数据去加载区块
        }""",
  """        if (level instanceof final Level realLevel && SafeChunkRead.chunkNow(realLevel, pos) == null) {
            return null; // 未加载（或未到 FULL）：绝不为了取一份数据去加载区块 —— 第 138 轮安全读
        }""", 1),
 ("""        if (!level.isLoaded(pos)) {
            return; // 绝不为了补一次同步去加载区块（与 dataOf 同一条纪律）
        }
        final BlockEntity be = level.getBlockEntity(pos);""",
  """        // 第 138 轮：安全读（只读已 FULL 区块）—— 绝不为了补一次同步去加载区块
        final BlockEntity be = SafeChunkRead.blockEntityNow(level, pos);""", 1),
], False)

add("support/RsccCableCuts.java", [
 ("""        final BlockPos other = pos.relative(dir);
        if (!level.isLoaded(pos) || !level.isLoaded(other)) {
            return false;
        }
        return RsccWireBlocks.isWire(level.getBlockState(pos))
            && RsccWireBlocks.isWire(level.getBlockState(other));""",
  """        final BlockPos other = pos.relative(dir);
        // 第 138 轮：安全读（只读已 FULL 区块）
        final BlockState selfState = SafeChunkRead.blockStateNow(level, pos);
        final BlockState otherState = SafeChunkRead.blockStateNow(level, other);
        return selfState != null && otherState != null
            && RsccWireBlocks.isWire(selfState)
            && RsccWireBlocks.isWire(otherState);""", 1),
], False)

add("support/RsccBusLinkInvalidation.java", [
 ("""            if (!level.isLoaded(neighbor)) {
                continue;
            }
            // 先按方块状态确认「是输出总线 / 输入总线方块」再取方块实体（CHECK：只取已存在的）
            final BlockState state = level.getBlockState(neighbor);""",
  """            // 第 138 轮：安全读（只读已 FULL 区块，不加载、不阻塞）
            // 先按方块状态确认「是输出总线 / 输入总线方块」再取方块实体（CHECK：只取已存在的）
            final BlockState state = SafeChunkRead.blockStateNow(level, neighbor);
            if (state == null) {
                continue;
            }""", 1),
], False)

add("compat/crafter/OutputReclaim.java", [
 ("""        if (!level.isLoaded(pos)) {
            return null;
        }
        final BlockEntity blockEntity = level.getBlockEntity(pos);""",
  """        // 第 138 轮：安全读（只读已 FULL 区块，不加载、不阻塞）
        final BlockEntity blockEntity = SafeChunkRead.blockEntityNow(level, pos);""", 1),
], True)

add("compat/crafter/MechanicalCraftPlan.java", [
 ("""        if (!level.isLoaded(pos)) {
            return null;
        }
        return level.getBlockEntity(pos) instanceof MechanicalCrafterBlockEntity crafter ? crafter : null;""",
  """        // 第 138 轮：安全读（只读已 FULL 区块，不加载、不阻塞）
        return SafeChunkRead.blockEntityNow(level, pos) instanceof MechanicalCrafterBlockEntity crafter
            ? crafter : null;""", 1),
], True)

add("compat/crafter/CreateCraftTargets.java", [
 ("""            if (!level.isLoaded(nextPos)) {
                return current;
            }
            final BlockEntity next = level.getBlockEntity(nextPos);""",
  """            final BlockEntity next = SafeChunkRead.blockEntityNow(level, nextPos);""", 1),
 ("""        if (!level.isLoaded(targetPos)) {
            return null;
        }
        return level.getBlockEntity(targetPos);""",
  """        return SafeChunkRead.blockEntityNow(level, targetPos);""", 1),
], True)

PKT = """            if (!level.isLoaded(packet.pos())) {
                return;
            }
            if (!(level.getBlockEntity(packet.pos()) instanceof SequenceExecutionChamberBlockEntity byPos)) {"""
PKT_NEW = """            // 第 138 轮：安全读（只读已 FULL 区块，不加载、不阻塞）
            if (!(SafeChunkRead.blockEntityNow(level, packet.pos())
                instanceof SequenceExecutionChamberBlockEntity byPos)) {"""
for f in ("network/SetChamberBindingPacket.java", "network/SetChamberFacePacket.java",
          "network/SetChamberOutputModePacket.java"):
    add(f, [(PKT, PKT_NEW, 1)], True)

add("item/AssemblyPatternRebind.java", [
 ("""                    if (!level.isLoaded(pos)
                        || !(level.getBlockState(pos).getBlock()
                            instanceof SequenceExecutionChamberBlock)) {
                        continue; // 未加载 / 不是执行舱：一律跳过（绝不因取网络而加载区块）
                    }
                    if (level.getBlockEntity(pos) instanceof SequenceExecutionChamberBlockEntity chamber) {""",
  """                    // 第 138 轮：安全读（只读已 FULL 区块）—— 未加载 / 不是执行舱：一律跳过
                    final BlockState posState = SafeChunkRead.blockStateNow(level, pos);
                    if (posState == null || !(posState.getBlock() instanceof SequenceExecutionChamberBlock)) {
                        continue;
                    }
                    if (SafeChunkRead.blockEntityNow(level, pos) instanceof SequenceExecutionChamberBlockEntity chamber) {""", 1),
], True)

add("item/AdvancedRemoteTerminalItem.java", [
 ("""        if (boundLevel == null || !boundLevel.isLoaded(location.pos())) {
            return Optional.empty(); // 绑定位置未加载
        }
        final net.minecraft.world.level.block.entity.BlockEntity boundBe = boundLevel.getBlockEntity(location.pos());""",
  """        if (boundLevel == null) {
            return Optional.empty();
        }
        // 第 138 轮：安全读（只读已 FULL 区块）—— 绑定位置未加载 ⇒ 视为没有网络
        final net.minecraft.world.level.block.entity.BlockEntity boundBe =
            SafeChunkRead.blockEntityNow(boundLevel, location.pos());""", 1),
], True)

fail = 0
for rel, pairs, add_import in EDITS:
    path = os.path.join(SRC, rel.replace("/", os.sep))
    text = io.open(path, encoding="utf-8").read()
    file_ok = True
    for old, new, count in pairs:
        got = text.count(old)
        if got != count:
            print("FAIL count %d != %d in %s :: %r" % (got, count, rel, old[:60]))
            fail += 1
            file_ok = False
            continue
        text = text.replace(old, new, count)
    if add_import and IMPORT_LINE not in text:
        # 插到 package 行之后（Java 允许 import 紧跟 package）
        lines = text.split("\n")
        for i, line in enumerate(lines):
            if line.startswith("package "):
                lines.insert(i + 1, IMPORT_LINE)
                break
        text = "\n".join(lines)
    if file_ok:
        io.open(path, "w", encoding="utf-8", newline="\n").write(text)
        print("OK %s" % rel)

print("FAILURES=%d" % fail)
sys.exit(1 if fail else 0)
