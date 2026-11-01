# 第 113 轮（1.2.0-beta.15）：区域标记链路加固 —— 独立复核发现的 12 条缺陷与修复

> 本文是第 113 轮的唯一交付说明。上一轮（第 112 轮 / beta.14）把「每个区域的 ghost 过滤标记」从
> 方块实体搬到**区域选定器物品**（`support/AreaMarks`，`CUSTOM_DATA` + 六坐标几何键），并新增了
> 独立编辑界面 `client/screen/AreaMarkScreen` 与 `network/AreaMarkEditPacket`。
> 这轮**没有**新的用户实机反馈，因此把这一轮用来做两件「用户验收前必须做掉」的事：
> ① **独立对抗复核**（只读、不信任何既有结论）；② **端到端行为模型自检**（补上第 112 轮没有覆盖的
> 「手持配标记 → 放进机器 → 真的按这份标记吸收」「旧档迁移」两条链）。
> 复核共报出 3 条高危 + 3 条中危 + 6 条低危缺陷，**全部在本轮修完**。

---

## 1. 为什么有必要复核（不是走过场）

第 112 轮由单个代理一轮写成，自检 `selfcheck_round112_area_marks.py` 的 96 条断言**全是源码锚点 +
孪生模型**。本工程此前已出现两次「编译过 + 自检绿但真机出事」的先例（菜单槽位数不一致导致断线、
`IItemHandlerModifiable` 强转导致吞物品），所以这一轮专门找一个只读复核员，按
「写 → 存 → 读 → 用」走完整链路，只信代码与字节码。

复核结论：**没有**会崩 / 断线 / 丢真实物品的路径；但**有 3 条会丢玩家配置**的高危问题，
且都在既有自检的覆盖之外。

---

## 2. 缺陷清单与处置（12 条）

| 编号 | 严重度 | 缺陷 | 处置 |
|---|---|---|---|
| D1 | **高**（行为回归） | 区域列表里改坐标（`ACTION_REPLACE`）会把这个区域的**全部过滤标记删掉**。原因：REPLACE 换了几何，而标记以几何为键，随后 `AreaMarks.prune` 把「旧几何」当不存在剪掉。beta.13 的键是序号，改坐标不丢 —— 属于回归。 | **已修**：REPLACE 前先读旧几何的标记，写回后搬到新几何；新几何已有标记时按 `(id, fluid)` 去重合并，**绝不覆盖**已有条目（`AreaSelectorEditPacket`）。 |
| D2 | **高** | 旧档迁移在「一条都没映射上」时仍清空旧表：插一把**空白 / 区域为空**的选定器进旧机器，`migrateByIndex` 全部越界跳过、`migrated = 0`，但 `legacyAreaFilters.clear()` 照跑并写回存档 ⇒ 旧机器的每区域过滤**永久消失**，只留一句「已迁（0 个区域）」的 INFO。 | **已修**：只删「已映射」的旧条目（`keySet().removeIf(index < areaCount)`）；未映射的**原样留在存档里**并 WARN（`legacyMigrationWarned` 节流），等区域表恢复后再迁。 |
| D3 | 中高 | 迁移用 `sanitize` 后的区域表 ⇒ 管理员把 `areaSelectorMaxAreas / MaxVolume` 调小后，超限区域的标记被跳过，随后（D2 的）清表把标记毁掉，与「配置调回大值即恢复」的承诺相反。 | **已修**（随 D2）：未映射条目保留在 NBT 里，配置调回后本次读档会重新尝试迁移。 |
| D4 | 中 | 「物品上已有任意一条新标记 ⇒ 旧表整张丢弃」且**无日志**：升级后先用手持界面给区域 B 配了标记，再把选定器插回旧机器 ⇒ 区域 A 的旧标记全丢、玩家零反馈。 | **已修**：改成逐区域合并（物品是权威、只跳过该几何），跳过的条目打 WARN。 |
| D5 | 中（API 陷阱） | `AreaMarks.prune(stack, null)` = **清空全部标记**，与它自己的 javadoc「只删真正不存在的几何」相反（public 且立即写物品）。 | **已修**：`selection == null` 直接 return（null = 没有区域表信息，不剪）。顺带把遍历改成**原始记录列表**，修掉「超过 64 个区域记录永远剪不掉」的幽灵 NBT。 |
| D6 | 中 | 单条标记没有大小上限：标记把「物品的整个组件补丁」编进 NBT，成书 / 带内容物的容器 / 大 NBT 模组物品一条就上 KB × `MATCH_CAPACITY = 288` 条 ⇒ 选定器 `CUSTOM_DATA` 可能到 MB 级，随物品同步时超出网络包上限（把自己踢下线）。而 `AreaMarks` javadoc 曾声称「防恶意包把物品 NBT 撑爆」。 | **已修**：新增 `AreaMarks.MAX_MARKER_BYTES = 2048` + `isOversized(...)`；**服务端**（`AreaMarkEditPacket` ADD）与**客户端**（`AreaMarkScreen` 预检 + `notice.too_complex` 文案）两处拦截。**如实说明**：机器侧 48 格 ghost 窗口是第 101 轮就有的旧路径，本轮**不改**它的语义（`AreaMarks` javadoc 写清这一点，不假装全仓都拦住了）。 |
| D7 | 中 | 服务端拒绝后主手路径**不回同步**：客户端在发包前乐观改了自己那份物品副本，被拒后副本残留 ⇒ 接着配标记会按服务端不存在的几何发包、被静默丢包，界面却弹「已加入标记」。 | **已修**：`reject(...)` 的 `menu == null` 分支补 `player.inventoryMenu.broadcastFullState()`，把主手物品真实状态推回客户端。 |
| D8 | 低 | 读路径把物品 `CUSTOM_DATA` 里的**实时** Tag 交给上层（`MarkerEntry.nbt`），未来任何 in-place 修改都会绕过 `update()`。 | 现状不改（调用方只读）；风险写进 `AreaMarks` 读路径注释。 |
| D9 | 低（潜在客户端 NPE） | 旧格式 `Item` 标记 + `registries == null`（客户端 tooltip / 界面调用点）⇒ `ItemStack.parseOptional(null, ...)` NPE。 | **已修**：`decodeMarkerTag` 的 `TAG_MARKER_ITEM` 分支开头 `if (registries == null) return null;`。 |
| D10 | 低（陈述≠实现） | 5 处 javadoc 与代码不符（a `AreaMarks`「超出部分读档时丢弃」；b `AreaMarkEditPacket`「写完都走 sync」；c「客户端无法伪造任意 NBT」；d `AreaSelectorEditPacket`「在 ctx.enqueueWork 里执行」；f 客户端 `targetStack` 会回退主手而服务端不会）。 | **已修**：(a)(b)(c)(d) 文案改成事实；(f) 客户端 `targetStack` 对 `containerId >= 0` **不再回退主手**（对不上就是空，与服务端同口径）。 |
| D11 | 低 | `all()` 按 64 区域上限截断，而 `prune` 依赖它 ⇒ 超上限的记录永远剪不掉。 | **已修**（随 D5）：`prune` 直接遍历原始 `Marks` 列表。 |
| D12 | 低 | `AreaMarks.count(...)` 死代码；`AreaMarkScreen` 每帧调 `marks()` 3 次。 | 死代码**已删**；每帧 3 次解码保留（上限 288 条，成本可忽略，记在这里不假装优化过）。 |

---

## 3. 修复落点（文件级）

| 文件 | 改动 |
|---|---|
| `support/AreaMarks.java` | `prune`：null 早退 + 原始列表遍历；删 `count`；新增 `MAX_MARKER_BYTES` / `isOversized`；`MergeResult` javadoc 写明「dropped 只表示本轮没映射上，不代表删数据」；类 javadoc 安全边界改成事实（读时截断不回写、新入口有单条上限）。`migrateByIndex` 保持「逐区域合并」语义（第 112 轮升级首日丢数据的问题在上一版已修）。 |
| `network/AreaSelectorEditPacket.java` | REPLACE **携带**旧几何标记（含去重合并）；`reject` 主手分支广播背包；`ctx.enqueueWork` 错误描述更正。 |
| `network/AreaMarkEditPacket.java` | ADD 单条标记大小上限（服务端兜底）；javadoc 更正「都走 sync」「无法伪造 NBT」。 |
| `block/entity/AdvancedCollectionCacheBlockEntity.java` | 迁移只删已映射条目 + `allMapped` + `legacyMigrationWarned` 节流 + 读档重置该标志；三条日志（info / 两条 warn）。 |
| `block/entity/CollectionCacheBlockEntity.java` | `decodeMarkerTag` 旧格式 `Item` 分支的 `registries == null` 早退。 |
| `client/screen/AreaMarkScreen.java` | 客户端单条标记大小预检 + `notice.too_complex`；`targetStack` 不再回退主手；区域体积文案走语言键（英文界面不会漏出中文「格」）。 |
| `lang/{zh_cn,en_us}.json` | 新增 `gui.rs_create_compat.area_mark.volume`、`gui.rs_create_compat.area_mark.notice.too_complex`（成对）。 |

---

## 4. 自检

### 4.1 新增：`tools/selfcheck_round113_area_mark_e2e.py`

**140 断言 / 38 条反例自证**（上一版 109/28）。六组行为模型 + 源码锚点：

1. 编码往返 / 几何键语义（改名不丢、区域互不串、删空即删子树、顺序 = 插入顺序）。
2. 旧档迁移：真实 `{Index, Entries}` 数据、**未映射条目保留**、区域表恢复后重试、`skipped` 也算已映射、幂等。
3. `AreaMarkEditPacket.handle` 的 11 道拒绝门（旁观者 / resolve 空 / 维度 / 伪造几何 / 容量 / 背包格 / id null / 重复 / REMOVE 越界 / 未知 action / 写后 sync），每道配一条「删掉该行 ⇒ 判负」的自证。
4. 端到端吸收口径（区域 A 只吸 iron、区域 B 空标记 = 什么都不吸、清空后立刻不吸、重叠区域先命中先算）。
5. 物品携带性（换机器仍可读；BE 侧**没有**独立的标记表，唯一载体是槽里物品的 NBT）。
6. 容量 / 畸形 NBT 边界（读侧截断不抛、14 种畸形取值 × 5 层不解抛）。
7. **第 113 轮复核修复组**：REPLACE 携带（含去重 / 不覆盖 amount）、主手拒绝广播、`prune(null)` 早退 + 原始列表、`MAX_MARKER_BYTES` 两处调用点 + 语言键成对、`targetStack` 不回退主手、`decodeMarkerTag` 的 registries 早退。

**判别力自证**：在 `/tmp` 的源码副本上做了 **11 处「打坏实现」**（不搬运 / 搬运顺序反转 / 不广播 /
prune 不早退 / prune 不走原始列表 / 删服务端 `isOversized` / `targetStack` 回退主手 / 删 registries 早退 /
迁移改回无条件 clear / 第三条 WARN 改回 dropped 判定 / 该 WARN 降级 debug）⇒ **11/11 全部判负**，
仓库 `src/` 零改动。

### 4.2 更新：`tools/selfcheck_round112_area_marks.py`

96 → **100 断言 / 12 条反例**：①-9 跟随 `MergeResult` 签名；⑤-10 改成「逐区域合并 + 未映射保留 + WARN」；
⑤-10c 新增「迁移方法里不许再有无条件 `legacyAreaFilters.clear()`」；⑤-16/⑤-17 两条反例
（整张丢弃回归 / 无条件清表回归）。旧锚点按实现诚实更新，**判据没有削弱**。

---

## 5. 本轮验收

1. `tools/_lead_compile.ps1` → `LEAD_COMPILE_OK sources=499`
2. 全量自检 → **159 个脚本 / 0 失败**
3. `tools/verify_dedicated_server.ps1` → **PASS**（`Done (2.024s)`，无 Mixin / dist 致命标记，`run/` 只读自证 OK）
4. `gradlew build` → `rs_create_compat-1.2.0-beta.15.jar`（**2,788,186 B**，
   sha256 `5311d7e3129a70637b2c0ea6a23fabd6d37f97d35a44a89e0da3b734e6d4cc91`）
5. 三处部署回读 sha256 一致（`tools/_verify_deploy_round113.py` → `DEPLOY_VERIFY_OK`：
   本地实例 / SFTP `/mods` / SFTP `/client_files/mods`），beta.14 就地改名 `.disabled`

## 6. 未验证 / 待确认（诚实清单）

1. **没有实机验证**：REPLACE 携带标记、旧档迁移的「保留未映射条目」、单条上限提示，都只有
   源码锚点 + 行为模型；本轮**没有**再跑进游戏点界面。
2. 复核员**未能 javap 证明**两件事（本机无 1.21.1 命名 jar）：`CustomData#update` 是否返回新实例
   （若不是，物品副本之间会共享数据）；`ItemStack.parseOptional(null, …)` 的 NPE 只是按契约推断
   （D9 已用早退兜住）。
3. beta.13 旧 `AreaFilters` 的 `Index` 到底是「sanitize 后可用位次」还是「原始下标」仍未拿到旧源码
   佐证 —— 迁移的序号口径据此推断（D3 的置信度受此限制）。
4. 网络包大小上限的具体阈值没有实测（D6 的 2 KB 是保守值）。
5. 机器侧 48 格 ghost 窗口仍可写入大 NBT 标记（第 101 轮的旧路径，本轮按「不改旧语义」处理，
   已在 `AreaMarks` javadoc 与本文件写明，不假装拦住了）。
6. 重叠区域「先命中先算」（列表里靠前的空标记区域会挡住后面区域的标记）是既有行为，本轮未改，
   UI 也还没有提示。

## 7. 下一轮候选

- **用户对 beta.14 / beta.15 的实机反馈（最高优先）**。
- 「区域标记」界面 36 格上限是否分页 / 是否把数量门槛与标签匹配搬进来。
- 给「打开区域列表」加快捷键入口。
- 若拿到真实 beta.13 存档：跑一次真实迁移（含删过区域的老档），确认 WARN 与保留逻辑。
