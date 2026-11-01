# 交接单：第 120 轮（1.2.0-beta.22）收尾 —— 给下一个 DSH 会话

> 生成时间：2026-10-31 19:5x（Asia/Shanghai）。
> 工作区：`D:\MODS\refined_storage_and_create_compat`（MC 1.21.1 / NeoForge 21.1.248 / RS 2.0.9 / Create 6）。
> 本轮**已完整交付并收尾**，goal 已 `pause`。没有正在跑的编译 / 自检 / 构建 / 队友（已逐项核对）。

---

## 0. 一句话状态

`1.2.0-beta.22` 已构建、已三处部署、四项验收全绿；本轮没有未完成的代码改动，任务板为空（无 pending / in_progress）。
**唯一需要做的是等用户对 beta.22（以及 beta.14～beta.22）的实机反馈**，然后开下一轮。

---

## 1. 任务板状态

| 项 | 状态 |
| --- | --- |
| 未完成 / 未开始的任务 | **无**（`team_task_list(status=pending/in_progress)` 均为空） |
| 本轮三个任务 | `task-55`（A：序列家族 F2/F5/F6–F8）= completed，负责人 `rx-tx-chain`；`task-56`（B：汇总窗口 F3）= completed，负责人 `menu-type-fix-verify`；`task-57`（C：区域标记 C-5）= completed，负责人 `proxy-cache-area` |
| 队友 | 全部 `inactive`（没有正在写文件的队友，改动已全部落盘） |
| 后台 job | 全部 `completed`（最后一个是第 120 轮专服探针 `pwsh-b3dbb04ef5b8438b`，PASS） |

## 2. 本轮进展（已完成，无半截改动）

按第 119 轮两路只读复核「留到下一轮」的账清完 7 条 + 同源小偏差 3 条，**不改产品行为 / 玩法语义**：

1. **F2（中）外部样板 sink 整批原子性** —— `block/entity/SequenceAssemblyExecutorBlockEntity#accept`：
   RS 2.0.9 里 `REJECTED` 不会重试（源码原话 "we lie … potentially void"），旧实现网络快满时后半批被吞。
   现在按资源聚合 → 全量 SIMULATE 闸（一条不满则一条不插）→ 全过才 EXECUTE；`amount<=0` 不再凭空 +1。
   **边界（未修，别当已修）**：同桶多资源合计超容 / 模拟与执行间竞态 → 只能「不劣于旧实现」；
   真相需执行仓自建上游缓冲 = 产品行为变更（待拍板）。详见 `docs/ROUND120_SEQ_AUDIT_DEBT.md`
   （含对 `ROUND119_AUDIT_SEQUENCE_CHAMBER.md` §F2 示例推理的**勘误**）。
2. **F3（中·界面）汇总窗口「看着 A 点到 B」** —— `menu/ChamberUnitsSummaryMenu.java`（513→774 行）：
   交互 8 tick 静默 + 轮转后 4 tick 安全窗（窗内点击不执行 + 全量重同步）+ 一次点击一份映射快照 +
   取走留「洞」放回原槽；顺带修「不足 4 张显示重复」与「>1 张就轮转」。详见 `docs/ROUND120_SUMMARY_WINDOW.md`。
3. **C-5（低）区域标记删错条** —— `client/screen/AreaMarkScreen.java` 客户端读标记改带
   `ClientLevel#registryAccess()`，拿不到注册表返回空表、绝不回退。详见 `docs/ROUND120_AREA_MARK_REGISTRY.md`。
4. **F5–F8（低）** —— `SetStepMachinePacket.name` 服务端改用执行仓自己的显示名 + 夹 256 码点；
   删死代码 `putUnit/takeUnit`；三处错注释改成实际值（54 格 = 6×9）；`dropOutputContents` 改名
   `rscc$legacyDropOutputContents` + `@Deprecated`；终端 `saveAdditional` 重复 `writeCandidates` 删一处。
5. 文档/版本：`gradle.properties` = `1.2.0-beta.22`；`CHANGELOG.md`（中英两条）、`README.md` /
   `README_en.md`（版本行）、`TECHNICAL_HANDOFF.md`（新增 §26，含 26.9 下一轮候选）。

## 3. 本轮验收证据（可直接复核）

- 编译：`powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\_lead_compile.ps1` → `LEAD_COMPILE_OK sources=504`
- 全量自检：`"C:\Users\70432\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe" tools\_run_all_selfchecks.py`
  → **174 个脚本 / 0 失败**（新增 3 个脚本共 153 断言 / 43 反例）
- 专服探针：`powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\verify_dedicated_server.ps1`
  → **PASS**（`Done (1.681s)`，无 Mixin/dist 致命标记，`run/` 只读自证 OK；日志 `build/dedicated_server_probe.log`）
- 构建：`./gradlew build --no-daemon --no-configuration-cache --console=plain` → BUILD SUCCESSFUL
  → `build/libs/rs_create_compat-1.2.0-beta.22.jar`（**2,819,238 B**，
  sha256 `df1a52d74e7a5c7d98e6cedd99576b83ec3f6d38c19598b1973ff19ac1a265ba`）
- 部署：`D:\Python313\python.exe tools\deploy_rscc.py --apply` + `D:\Python313\python.exe tools\deploy_rscc_local.py --apply`
  （**SFTP 必须用 `D:\Python313\python.exe`，bundled python 没有 paramiko**）
- 三处回读：`D:\Python313\python.exe tools\_verify_deploy_round120.py` → **`DEPLOY_VERIFY_OK`**
  （本地实例 / SFTP `/mods` / SFTP `/client_files/mods` 三处 sha256 全等；beta.21 就地 `.disabled`）

## 4. 仓库状态提醒

- 最后一次 git 提交仍是 `1.1.0`（`5a25047`）——**1.2.0 的全部工作都在工作树里未提交**（约 453 个未跟踪文件 +
  若干已修改文件），这是本工程既往多轮的既定状态，**不要擅自 commit / push**。
- `.sftp_key`、`diag/`、`assets/`、`tools/__pycache__/`、`_mcmod_work/` 等均为本地资产/日志，
  **永不把自检、绘图、SFTP 相关资产上传到任何地方**（用户硬要求）。

## 5. 下一步计划（开新一轮时的顺序，仅记录，未开工）

1. **最高优先**：读用户对 beta.14～beta.22 的实机反馈（本轮仍在等）；顺带把「实机跑的是哪个版本」问清
   （第 119 轮已查明「非得把标记放在方块里」在 beta.14 后已不成立，仍需用户确认版本）。
2. 序列执行仓 **F4**（`inFlightUnits` 不落盘 ⇒ 重启后工位残留失去回收凭据、机器被占死）；
   F2 真修法（上游缓冲）；`SetChamberBindingPacket.name` 无长度上限。
3. 区域标记：**C-4**（`AREA_LIST_SLOT_MAX=16` vs Config 64 ⇒ 第 17 区以后计数恒 0，分页 vs 抬上限**需拍板**）/
   **C-7**（区域远离时实体查询并集盒巨大）/ C-5 后续（按内容删除、`AreaSelectorItem` tooltip 计数）。
4. 汇总窗口：`pollCounter` 改游戏时间；方案 B 强化版（协议层回执）消除 RTT>200 ms 残余窗口。
5. 其它：`tools/simplify_lang_texts.py` 过时「6 格」文案；保持器三项产品口径；`/rs_create_compat diag` 权限口径；
   「只高亮某一个区域」是否需要 per-area 开关（动 `Area` 数据契约，必须先确认）。
6. 每轮固定收尾流程（可照抄本轮）：改码 → `_lead_compile.ps1`（LEAD_COMPILE_OK）→ 全量自检 174/0 →
   专服探针 PASS → `gradlew build` → 部署本地 + SFTP 两处 → `_verify_deploy_roundNNN.py` 三处 sha256 一致 →
   更新 CHANGELOG（中英）/ README 版本行 / TECHNICAL_HANDOFF 新一节 → 报给用户。

## 6. 恢复方式

- goal 目前为 **paused**（`goal_id = goal-6a572c90-c2bc-4686-b5c9-a250ef88876e`，revision 4）。
  用户在任意会话里说「继续 / 接着做 / resume」时，先 `get_goal` 取最新 revision，再
  `update_goal(action=resume)` 重新激活，然后按第 5 节顺序开新一轮。
- 本轮**没有**未完成的写入：所有队友已 inactive、所有 job 已 completed、所有文件已落盘。
