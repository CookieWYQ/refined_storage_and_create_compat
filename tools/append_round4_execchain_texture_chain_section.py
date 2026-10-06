# -*- coding: utf-8 -*-
"""把「执行舱贴图/模型重做 + 成链逐条验证」章节**幂等**追加进 docs/DESIGN_DECISIONS_ROUND4.md。

用法：python tools/append_round4_execchain_texture_chain_section.py
"""
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOC = os.path.join(ROOT, "docs", "DESIGN_DECISIONS_ROUND4.md")

MARK = "## 执行舱贴图/模型重做 + 「成链」逐条验证（本轮）"

SECTION = """

***

""" + MARK + """

> 来源：用户两条意见 —— ①「执行仓应该像自动合成舱那样，只有一个指向性明确的贴图：
> 周围四个面是相同贴图、顶部一个、底部一个，四个侧面贴图带指向性」；
> ②「貌似现在也没看出来你那个成链的功能完成了」；
> ③「以后写贴图不要全部共用一个贴图」。本轮把 ①③ 落地，并把 ② 逐条验证 + 补可见性。

### 1. 执行舱外观：照 RS 自动合成舱（Autocrafter）的结构

**模型朝向约定**（与 RS 自动合成舱、原版观察者完全一致）：模型**以「前端面 = UP 面」烘焙**，
blockstate 用 `x:90` 系列旋转把前端面转到 `facing` 指的方向：

| `facing` | 模型旋转 | 与 RS 的对照 |
| --- | --- | --- |
| `north` | `x:90` | `direction=north` 同值 |
| `east` | `x:90, y:90` | `direction=east` 同值 |
| `south` | `x:90, y:180` | `direction=south` 同值 |
| `west` | `x:90, y:270` | `direction=west` 同值 |

这样 **只有侧面那一张贴图带指向性**，且箭头（贴图内朝上）在游戏里恒指向「机器侧 / 朝向」——
正是 RS 自动合成舱的表现；顶/底（模型 up/down 键）分别是「连接口」与「背板」，各自独立成图。

贴图（16×16，RS 灰色机壳底图 + 本模组青色强调色；**互不共用**）：

| 文件 | 用在哪 | 说明 |
| --- | --- | --- |
| `block/sequence_execution_chamber_side[_active].png` | 模型 N/E/S/W 四面 | 带指向前端面的箭头（同一台机器的四个侧面共用，用户明确允许） |
| `block/sequence_execution_chamber_front[_active].png` | 模型 UP = 前端面（指向 `facing`） | 发光方形接口 |
| `block/sequence_execution_chamber_back[_active].png` | 模型 DOWN = 背面 | 散热格栅 |

* 生成脚本：`tools/gen_chamber_faces.py`（幂等；同时把 6 张图与 4 倍预览条留档到
  `tmp_textures/chamber_faces/`）；模型/blockstate：`tools/gen_face_split_models.py`。
* **旧贴图清理**：`sequence_execution_chamber.png`（历史遗留、无人引用）、
  `sequence_execution_chamber_active.png`、`sequence_execution_chamber_inactive.png` 三张「六面一张图」已删除。
* **旧存档平滑**：方块状态属性 `facing` / `active` **一个都没变**（只改了贴图与模型旋转的对应关系），
  旧档里的执行舱读档后朝向与朝向语义完全不变，无需任何数据迁移。

### 2. 「成链」功能：逐条验证结论（读代码 + 逐项核对，不是"看界面猜"）

| 验证项 | 结论 | 依据 |
| --- | --- | --- |
| 名字是否同步到全链 | ✅ 真同步 | `getChamberName()` 读 `chainHead()`、`setChamberName()` 写 `chainHead()`（成员自动转发）⇒ 改一处 = 全链生效 |
| 配方类型是否同步到全链 | ✅ 真同步 | 同上，`getRecipeType()` / `setRecipeType()` 全部委托链首；链上任一台还有单元样板时服务端锁定配方类型 |
| 在哪设置指向 / 是否好找 | ✅ 有可见入口（本轮增强） | 主界面标题栏「配置」→ 子界面底部「链指向」滚轮（几何见 `tmp_textures/CHAMBER_BINDING_CONFIG_GUI_DOC.md`）。本轮把链信息提到**主界面**：状态行显示「配方类型：X（链 N 台 · 指向 北）」，悬停 tooltip 多出「本台指向 / 链头 / 从哪进设置」三行 |
| 分叉是否被拒绝 | ✅ 拒绝 | `setChainLink` 里 `hasOtherPredecessor` ⇒ `LinkResult.FORK`，行动栏提示「该仓已被另一台指向」 |
| 成环是否被拒绝 | ✅ 拒绝 | `reachesMe(target)`（判「从 target 沿指向走能否**经过**本仓」，比只比链首严格）⇒ `LinkResult.CYCLE` |
| 读档后链能否重建 | ✅ 能 | `chainLink` 进 NBT（`saveAdditional/loadAdditional` 的 `ChainLink`）；链**不进任何缓存**，`chainHead()/chainMembers()` 每次按当前世界状态现推；旧档遗留的分叉/环由 `sanitizeRayServer()` 一次性收敛成射线（只取消指向，绝不销毁配置） |

另外三条硬约束也复核过：链长上限 8（`MAX_CHAIN_LENGTH`，照 RS 的 `MAX_CHAINED_AUTOCRAFTERS`）、
**入度 ≤ 1**（射线不分叉）、拒绝路径一律回发权威值（界面不会停在假象上）。

### 3. 玩家最短操作路径（照这个走就能串链）

1. 摆好 2 台以上执行仓（相邻或隔着别的方块都行，只要「指向」能指到）；
2. 任意一台**右键**打开界面 → 点标题栏右侧的「**配置**」按钮；
3. 在子界面**底部**的「**链指向**」滚轮上选方向（就是朝邻居那一台），点「确定」；
4. 回到主界面：状态行会显示「配方类型：X（链 N 台 · 指向 北）」——**悬停这一行**可看到链头是谁、
   以及「设置链指向：点本界面上方的『配置』按钮…」；
5. 之后**改名字 / 改配方类型**：在链上**任意一台**改，整条链一起生效（链上任一台仍放着单元样板时，
   配方类型会被锁定，必须先把样板取走）。

### 4. 共用贴图审计（新脚本 `tools/audit_shared_textures.py`）

判据：把每个模型的贴图引用展开成「面 → PNG」，**同一张 PNG 落在多个不同的面上**即为一图多用；
豁免仅一种：恰好落在 `north/east/south/west`（同一台机器的四个竖直侧面，用户明确允许）。
原版父模型（`orientable[_with_bottom]` / `cube[_all]` …）按内置映射展开面，避免漏判。

**本轮修正**（`tools/gen_block_face_split.py` + `tools/gen_face_split_models.py`，均已幂等）：

| 方块 | 修正前 | 修正后 |
| --- | --- | --- |
| 序列执行舱 | `cube_all`（六面一张图） | side / front / back 三张，见 §1 |
| 单元样板管理舱 | `orientable_with_bottom`（背面复用侧面、底面复用顶面） | 六面各一张：front / back / left / right / top / bottom（RS 自动合成管理舱本来就是五张独立贴图 + 通用 bottom），旧 `*_side*.png` 已删除 |
| 蓝图装填器 / 高级蓝图装填器 / 归流缓存仓 / 范围充电器 / 样板库 | `cube_all`（六面一张图） | 四个侧面共用 side、顶面 top、底面 bottom（三张各自独立；底面还带本方块强调色接缝） |

顺手清理的死图：`item_collector_active.png` / `item_collector_inactive.png`（方块早已改名成
`collection_cache`，全工程无人引用）、`range_charger.png`（无人引用的历史遗留）。

**按硬规则排除、只报告不改**（这几路贴图由其它并行任务在改，本轮不得触碰）：
`quantity_keeper` / `advanced_quantity_keeper`（保持器）、`sequence_pattern_terminal`（SPT）、
`sequence_return_bus`（回流总线）。它们的「一图多用」写在审计脚本输出的 `[排除]` 行里，含原因。

### 5. 本轮改动文件

新增：`tools/gen_chamber_faces.py`、`tools/gen_block_face_split.py`、`tools/gen_face_split_models.py`、
`tools/gen_lang_frag_execchain2.py` + `tools/lang_frag_execchain2.json`、`tools/audit_shared_textures.py`、
`tmp_textures/chamber_faces/` 与 `tmp_textures/face_split/`（预览留档）。

修改：`blockstates/sequence_execution_chamber.json`（x:90 系列旋转）、
`models/block/sequence_execution_chamber[_active].json`、
`models/block/{schematic_loader,advanced_schematic_loader,collection_cache,range_charger,sequence_assembly_executor}[_active].json`、
`models/block/unit_pattern_manager[_active].json` + `tools/gen_unit_manager_resources.py`、
`client/screen/SequenceExecutionChamberScreen.java`（主界面显示链指向 + tooltip 补链头 / 设置入口）、
`assets/rs_create_compat/lang/{zh_cn,en_us}.json`（经 `apply_lang_frag.py`，+3 键、-1 键）。
"""


def main():
    with open(DOC, "r", encoding="utf-8") as handle:
        text = handle.read()
    if MARK in text:
        print("[跳过] 章节已存在: %s" % MARK)
        return 0
    with open(DOC, "a", encoding="utf-8", newline="\n") as handle:
        handle.write(SECTION)
    print("[追加] %s（+%d 字符）" % (os.path.relpath(DOC, ROOT), len(SECTION)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
