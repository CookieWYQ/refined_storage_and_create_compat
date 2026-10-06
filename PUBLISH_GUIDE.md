# 发布速查：MCmod百科 / Modrinth / CurseForge

> 面向本模组 `rs_create_compat` `1.0.0` 的第三方站点发布操作手册。
> 配套文件：`PUBLISH_MCMOD.md`（mcmod.cn 条目内容）、`PUBLISH_MODRINTH.md`（Modrinth / CurseForge 描述）。
> 本文只描述操作，不改动工程内任何文件。

---

## 0. 本次发布的基本事实（填写时照抄，勿改写）

| 项 | 值 | 依据 |
| --- | --- | --- |
| 显示名 | 机械动力&精致存储：兼容与改善 | `gradle.properties` 的 `mod_name`（`neoforge.mods.toml` 用它作 `displayName`） |
| 模组 ID | `rs_create_compat` | `gradle.properties` 的 `mod_id` |
| 版本 | 1.0.0（首个正式版本） | `gradle.properties` 的 `mod_version` |
| 作者 | CallMeACookieWYQ | `gradle.properties` 的 `mod_authors` |
| 许可 | All Rights Reserved | `gradle.properties` 的 `mod_license` |
| 支持 MC 版本 | 1.21.1 | `gradle.properties` 的 `minecraft_version` |
| 加载器 | NeoForge `[21,)`（基线 21.1.248） | `gradle.properties` 的 `neo_version` / `neo_version_range` |
| Java | Java 21 | `build.gradle` 的 `JavaLanguageVersion.of(21)` |
| 制品 | `build/libs/rs_create_compat-1.0.0.jar`（1,860,064 字节，约 1.77 MB） | 构建产物，已核对 |
| 仓库 | https://github.com/CookieWYQ/refined_storage_and_create_compat | `gradle.properties` / 仓库 |
| Release | https://github.com/CookieWYQ/refined_storage_and_create_compat/releases/tag/v1.0.0 | 仓库 Release |
| 问题反馈 | https://github.com/CookieWYQ/refined_storage_and_create_compat/issues | 仓库 |

---

## 1. MCmod百科（mcmod.cn）

### 1.1 提交步骤

1. **搜重。** 在 mcmod.cn 站内搜索 `rs_create_compat`、`机械动力&精致存储`、`兼容与改善` 三个关键词，并检查「精致存储」与「机械动力」两个模组条目的**联动 / 附属模组**列表。模组刚发布，通常搜不到；若已有条目，直接进入 1.3 的编辑流程，不要重复创建。
2. **确认资料入口。** mcmod.cn 的模组条目由用户提交、经审核后公开。若没有账号，先注册并完成基础资料（部分编辑权限与用户等级相关）。
3. **创建条目。** 在条目创建入口选择「模组」，先填最小必填集（中文名称、支持 MC 版本、加载器、来源），提交后进入编辑器补全其余字段。
4. **填入字段。** 打开 `PUBLISH_MCMOD.md` 的 **A 段字段表**，逐项复制。字段名以站点表单实际显示为准，本文按常见字段名给出对应值。
5. **填入正文。** 依次粘贴 A 段之后的 **B（模组简介）→ C（主要内容 / 特性）→ D（使用教程）→ E（指令与快捷键）→ F（常见问题）→ G（已知限制）→ H（更新记录）**。站点编辑器支持 Markdown 或富文本时，表格可能被转换，粘贴后需要目视检查表格是否串行。
6. **补前置模组。** 在「前置模组」字段里，把必装与可选前置都挂到**站点已有的对应模组条目**上（见 1.3 易错点）。
7. **补链接。** CurseForge / Modrinth 尚未发布时，这两栏留空或写「待发布」；GitHub 链接必须填。
8. **提交审核。** 提交后按审核反馈补齐资料；若被要求提供来源，附上 GitHub 仓库与 Release 链接即可。

### 1.2 必填与建议填写的字段

| 字段 | 本模组的填法 |
| --- | --- |
| 中文名称 | 机械动力&精致存储：兼容与改善 |
| 英文名称 | 未定义官方英文名（`displayName` 就是中文名），可留空或填写仓库名 `refined_storage_and_create_compat` |
| 来源 | 国外 |
| 类型 | 科技 |
| 支持 MC 版本 | 1.21.1 |
| 加载器 | NeoForge |
| Java 版本 | Java 21 |
| 前置模组 | 必装：机械动力、精致存储 2、Curios API、Refined Storage - Curios Integration；可选：JEI、Jade、FTB Ultimine |
| 作者 | CallMeACookieWYQ |
| 开源状态 | 否（仓库公开可浏览，但许可是 All Rights Reserved） |
| 许可 | All Rights Reserved |
| GitHub 链接 | https://github.com/CookieWYQ/refined_storage_and_create_compat |
| 模组 ID | `rs_create_compat` |

### 1.3 易错点

- **名称不要写错。** 本站口径为「精致存储」与「机械动力」；正文里不要写成「RS」「Create」代替这两者的官方中文名，首次出现时用全称，括号内可附英文。
- **前置模组要挂到正确的站点条目。** 特别注意两个容易挂错的：
  - **Refined Storage - Curios Integration** 是**独立于** Curios API 的另一个模组（提供能装下本模组远程终端的那个饰品槽），不要挂成 Curios API 本身；
  - **精致存储 2** 与旧版「精致存储」在站上可能是同一条目的不同版本分支。本模组要求 `[2.0.0,3.0.0)`，务必确认挂上的是 **2.x 分支**，挂成 1.x 会让读者装了也加载不了。
- **`All Rights Reserved` 直接决定「开源状态」填「否」。** 不要因为「GitHub 上能看见源码」就填「是」；「开源状态 = 是」在本站意味着存在开源许可（如 MIT / LGPL / GPL）。本模组的 `license` 字段值为 `All Rights Reserved`，正文里也要写明「再分发、修改与商用请先取得作者同意」。
- **不要把可选前置写成必装。** JEI / Jade 仅客户端；FTB Ultimine 只影响框架的连锁套壳。写成必装会让玩家误以为缺一不可。注意仓库内 `RELEASE_1.0.0.md` 的「可选安装」表已经把它们列为可选，`neoforge.mods.toml` 也标为 `type = "optional"`。
- **同一显示名的两件物品要合并表述。** `advanced_remote_terminal` 与 `advanced_remote_terminal_charged` 在语言文件中共用显示名「高级远程多功能终端」（英文同为 Advanced Remote Terminal），区别只是后者生成时满电。物品表里不要把它们写成两个不同名称的物品，可写成一行并注明「含满电版本」。
- **版本范围要照抄原文。** 例如精致存储是 `[2.0.0,3.0.0)` 而不是「2.0.0 以上」；机械动力只设下界 `[6.0,)`。写成「以上」会丢掉上界信息。
- **不要用营销口径。** 本站是百科，避免「最强」「神器」「必装模组」这类措辞，也不要出现「A 或 B」这种含糊表述（例如「支持 Fabric 或 NeoForge」）。
- **正文里的中文方块 / 物品名直接取自语言文件。** 详见本文第 5 节清单。

---

## 2. Modrinth

### 2.1 提交步骤

1. 登录 Modrinth，右上角头像 → **Create a project**。
2. 选择 **Mod** 作为项目类型。
3. **Project name** 填 `机械动力&精致存储：兼容与改善`。
4. **Summary** 从 `PUBLISH_MODRINTH.md` 的 A 段取一条（中文 ≤ 80 字或英文 ≤ 160 字符；站点按字符数硬性截断，超长会被拒）。
5. **Description** 把 `PUBLISH_MODRINTH.md` 的 B 段整体粘贴（中英双语，`---` 为分界）。
6. **Categories** 勾 `technology`、`storage`、`utility`。
7. **Loaders** 只勾 **NeoForge**（不要勾 Forge / Fabric / Quilt）。
8. **Game versions** 勾 `1.21.1`。
9. **Environment** 选 **Client and server**（必装前置的依赖声明都是 `side = "BOTH"`）。
10. **License** 选 **All Rights Reserved**（Modrinth 有该选项，直接选；不要为了发布方便改成 MIT）。
11. **Links** 填 Source 与 Issues，均指向 GitHub 仓库与它的 issues 页。
12. **Dependencies** 逐条添加并挂到**具体 project**（见 2.3 易错点）：Create、Refined Storage、Curios、Refined Storage Curios Integration 设为 **Required**；JEI、Jade、FTB Ultimine 设为 **Optional**。
13. 保存草稿后进入 **Versions → Create version**：填 Version number `1.0.0`、Channel `Release`、Game versions `1.21.1`、Loaders `NeoForge`，上传 `build/libs/rs_create_compat-1.0.0.jar`，Changelog 可直接使用 `RELEASE_1.0.0.md` 的正文。
14. 提交后自查：项目页上必装前置是否显示为 Required、可选前置是否显示为 Optional，且游戏版本只显示 1.21.1。

### 2.2 必填字段清单

| 字段 | 是否必填 | 本模组的填法 |
| --- | --- | --- |
| Project name | 必填 | 机械动力&精致存储：兼容与改善 |
| Summary | 必填 | 见 `PUBLISH_MODRINTH.md` A 段 |
| Description | 必填 | 见 `PUBLISH_MODRINTH.md` B 段 |
| Project type | 必填 | Mod |
| Categories | 必填 | technology / storage / utility |
| Loaders | 必填 | NeoForge |
| Game versions | 必填 | 1.21.1 |
| Environment | 必填 | Client and server |
| License | 必填 | All Rights Reserved |
| Links（Source / Issues） | 建议必填 | GitHub 仓库与 issues |
| Dependencies | 建议必填 | 四个 Required + 三个 Optional，挂到具体 project |
| Version number / Channel / File | 发布版本时必填 | 1.0.0 / Release / `rs_create_compat-1.0.0.jar` |

### 2.3 易错点

- **依赖必须挂到具体 project，不能只写文字。** 在 Dependencies 里搜索并选择站上真实存在的项目条目；写在 Description 里的依赖名不会产生任何加载期约束，也不会显示在「Dependencies」区块。
- **必装与可选要分开设置。** 四个必装前置（Create、Refined Storage、Curios、Refined Storage Curios Integration）设为 Required，三个可选前置（JEI、Jade、FTB Ultimine）设为 Optional。全部设成 Required 会把可选联动的玩家挡在门外；全部设成 Optional 则玩家可能在缺前置的情况下加载失败。
- **`Refined Storage Curios Integration` 不是 Curios API。** 站上两者都有条目，必须选名字里带 Curios Integration 的那一个；这个前置在 `neoforge.mods.toml` 里被列为 `required` 的原因是「数据文件无法做条件判断」，缺它会整块缺槽位。
- **`All Rights Reserved` 是可选项，不要绕开。** Modrinth 允许上传闭源模组；选择 ARR 后项目页会显示对应标记，不影响发布。
- **Game versions 只勾 1.21.1。** 多勾会让玩家在工作版本不符时下载到无法加载的文件。
- **Summary 会被硬性截断。** 中文摘要 52 字、英文摘要 109 字符（含空格），都在上限内；不要在其后再补副标题。
- **上传物只上传 jar。** Modrinth 不接受 zip；如果构建产物包含 sources jar，注意不要误传。
- **Changelog 与 Release 正文的一致性。** 建议 Changelog 直接取 `RELEASE_1.0.0.md`（它已经是按发布口径写好的），避免两处描述互相矛盾。

---

## 3. CurseForge

1. 登录 CurseForge，进入 Author Dashboard → **Create Project** → 选 **Mods**。
2. 填 `Project Name`、`Summary`、`Description`（字段内容与 Modrinth 一致：Summary 用 A 段，Description 用 B 段）。
3. `Category` 选择与科技 / 存储相关的分类；`Game Version` 选 `1.21.1`；`Mod Loader` 选 **NeoForge**；如出现 `Java Version` 字段，填 **Java 21**。
4. `License` 选 **All Rights Reserved**（若下拉里没有该选项，选择自定义许可并写「All Rights Reserved」）。
5. `Relations`（依赖关系）中把四个必装前置标为 **Required Dependency**，三个可选前置标为 **Optional Dependency**，同样要挂到具体项目。
6. 上传文件：`build/libs/rs_create_compat-1.0.0.jar`，版本号 `1.0.0`，Release type 选 **Release**。
7. 提交后等待审核；审核期间不要重复提交同一版本。

---

## 4. 上传物清单

| 类别 | 路径 / 内容 | 备注 |
| --- | --- | --- |
| 模组 jar | `build/libs/rs_create_compat-1.0.0.jar`（1,860,064 字节，约 1.77 MB） | 已核对存在；这是三个站唯一需要上传的制品 |
| 项目图标 | 仓库内**没有**可直接上传的模组图标 / logo | 详见下文「图标」小节 |
| mcmod 条目正文 | `PUBLISH_MCMOD.md` | 字段表 + B~H 段正文 |
| Modrinth / CurseForge 描述 | `PUBLISH_MODRINTH.md` | A 段作 Summary，B 段作 Description，C 段是自查表（**不要**发布 C 段） |
| 版本更新说明 | `RELEASE_1.0.0.md` | 可直接作为 Changelog；也对应 GitHub Release tag `v1.0.0` 的正文 |
| 详细说明 / 上手流程 | `README.md` | 内容最全，可作为站外「更多信息」链接或百科正文的补充来源 |
| 英文说明 | `README_en.md` | 英文站的备用资料 |

**图标**

- 仓库内**不存在**现成的模组图标或 logo 文件：`neoforge.mods.toml` 里的 `logoFile` 是注释状态，仓库根目录也没有 `logo.png` / `icon.png`。
- 根目录的 `pic/` 目录里只有 GUI 贴图，**不是**项目图标：`pic/bg.png`、`pic/slot.png`、`pic/slot_s.png`，以及 `pic/widget/`、`pic/rs_ref/` 下的界面精灵图。这些是界面底板与槽位素材，直接当图标使用会看不出模组内容。
- 如需图标，建议从模组的方块 / 物品贴图中挑一张导出并放大到站点要求的尺寸（通常 400×400 或 256×256），例如：
  - `src/main/resources/assets/rs_create_compat/textures/block/sequence_execution_chamber_front_active.png`
  - `src/main/resources/assets/rs_create_compat/textures/block/sequence_pattern_terminal.png`
  - `src/main/resources/assets/rs_create_compat/textures/item/sequence_assembly_pattern.png`
  - `src/main/resources/assets/rs_create_compat/textures/item/advanced_remote_terminal.png`
  这些文件均已确认存在，但它们是 16×16 的方块 / 物品贴图，需要自行放大与补边，仓库中没有已放大的版本。

---

## 5. 官方中文译名清单（正文命名一律以此为准）

来源：`src/main/resources/assets/rs_create_compat/lang/zh_cn.json`（`block.rs_create_compat.*` / `item.rs_create_compat.*`）。

**方块（14 条语言键）**

| 语言键 | 中文名 |
| --- | --- |
| `block.rs_create_compat.sequence_pattern_terminal` | 序列装配样板终端 |
| `block.rs_create_compat.sequence_assembly_executor` | 序列装配样板库 |
| `block.rs_create_compat.sequence_execution_chamber` | 序列执行仓 |
| `block.rs_create_compat.unit_pattern_manager` | 单元样板管理舱 |
| `block.rs_create_compat.intermediate_cache` | 中间产物缓存仓 |
| `block.rs_create_compat.collection_cache` | 归流缓存仓 |
| `block.rs_create_compat.quantity_keeper` | 资源定量保持器 |
| `block.rs_create_compat.advanced_quantity_keeper` | 高级资源定量保持器 |
| `block.rs_create_compat.schematic_loader` | 蓝图加农炮装填器 |
| `block.rs_create_compat.advanced_schematic_loader` | 高级蓝图加农炮装填器 |
| `block.rs_create_compat.range_charger` | 范围充电器 |
| `block.rs_create_compat.separation_frame` | 分隔框架 |
| `block.rs_create_compat.infinite_separation_frame` | 无限分隔框架 |
| `block.rs_create_compat.camouflage_frame` | 伪装框架 |

**物品（15 条语言键）**

| 语言键 | 中文名 |
| --- | --- |
| `item.rs_create_compat.sequence_assembly_pattern` | 序列装配样板 |
| `item.rs_create_compat.sequence_unit_pattern` | 序列装配单元样板 |
| `item.rs_create_compat.advanced_remote_terminal` | 高级远程多功能终端 |
| `item.rs_create_compat.advanced_remote_terminal_charged` | 高级远程多功能终端（与上一条同显示名，仅满电状态不同） |
| `item.rs_create_compat.creative_advanced_remote_terminal` | 创造高级远程多功能终端 |
| `item.rs_create_compat.universal_storage_disk_1k` | 1K 通用储存磁盘 |
| `item.rs_create_compat.universal_storage_disk_4k` | 4K 通用储存磁盘 |
| `item.rs_create_compat.universal_storage_disk_16k` | 16K 通用储存磁盘 |
| `item.rs_create_compat.universal_storage_disk_64k` | 64K 通用储存磁盘 |
| `item.rs_create_compat.universal_storage_disk_256k` | 256K 通用储存磁盘 |
| `item.rs_create_compat.universal_storage_disk_1m` | 1M 通用储存磁盘 |
| `item.rs_create_compat.universal_storage_disk_4m` | 4M 通用储存磁盘 |
| `item.rs_create_compat.universal_storage_disk_16m` | 16M 通用储存磁盘 |
| `item.rs_create_compat.universal_storage_disk_64m` | 64M 通用储存磁盘 |
| `item.rs_create_compat.universal_storage_disk_creative` | 无限 通用储存磁盘 |

> 注意两点：磁盘的中文名里 K / M 是大写、单位前有一个半角空格；创造版的名字是「无限 通用储存磁盘」（中间有空格），与英文 `Infinite Universal Storage Disk` 对应。

---

## 6. 发布后自查清单

- [ ] 三个站的模组名完全一致：机械动力&精致存储：兼容与改善
- [ ] 三个站的游戏版本只有 1.21.1，加载器只有 NeoForge
- [ ] 必装前置 4 个、可选前置 3 个，且在每一站都挂在正确的项目上
- [ ] 许可处处为 All Rights Reserved，开源状态填「否」
- [ ] GitHub 仓库与 issues 链接可用
- [ ] 上传的 jar 文件名与 Release 里引用的文件名一致（`rs_create_compat-1.0.0.jar`）
- [ ] 正文里的方块 / 物品名与第 5 节清单逐字一致
- [ ] 正文中不出现「最强」「神器」等营销措辞，也不出现「A 或 B」式含糊表述
- [ ] 「已知限制」段落如实保留（尤其是 FACE 输出模式未设防、挂起不自愈、样板需重新生成三条）
