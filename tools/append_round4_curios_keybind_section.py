# -*- coding: utf-8 -*-
"""把本轮的「Curios 必选前置 + 终端快捷键」章节**幂等**追加进 docs/DESIGN_DECISIONS_ROUND4.md。

为什么用脚本：该文档被多个并行子 agent 共同追加，手改整份文件容易冲突；
脚本按「标记存在即跳过」的方式追加，重复执行结果一致。

用法：
    python tools/append_round4_curios_keybind_section.py
"""
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOC = os.path.join(ROOT, "docs", "DESIGN_DECISIONS_ROUND4.md")

MARK = "## 第十七轮（2026-09-19）：Curios 必选前置 + 终端快捷键（默认 G）"

SECTION = """

***

""" + MARK + """

> 来源：用户本轮需求「把这个饰品栏模组（Curios）标记为前置（最终：**强必选**）」+
> 「可以指定一个快捷键来打开这一个终端，快捷键的方式和 MC 原版一样，都在按键绑定（Controls）里设置」+
> 「光标记为可选/必选还不够，**功能要真的能用**」。
> 硬规则不变：服务端权威、绝不销毁玩家资源、语言键走 `lang_frag_*` + `apply_lang_frag.py`。

### 1. Curios 的真实 modId 与依赖声明

* **真实 modId = `curios`**（`top.theillusivec4.curios.api.*` 只是它的包名，与 modId 无关；
  Curios 自己注册的物品栏能力 id 也是 `curios:item_handler`）。
* `src/main/templates/META-INF/neoforge.mods.toml` 新增（**必选**，不是 optional）：

```toml
[[dependencies."${mod_id}"]]
modId = "curios"
type = "required"
versionRange = "[9.0.0,10.0.0)"   # MC 1.21.1 对应 Curios 9.x
ordering = "AFTER"
side = "BOTH"
reason = "Required for the terminal Curios slot and the open-terminal keybinding."
```

* 1.21.1 的真实版本从「本地 Gradle 缓存里已解析到的制品元数据」读出：
  `D:\\gradle\\caches\\modules-2\\metadata-2.106\\descriptors\\top.theillusivec4.curios\\curios-neoforge\\9.2.3+1.21.1`。
  注意 **jar 本身不在本地缓存**（缓存里只有其它工程留下的 1.16.5 Forge 版），
  所以 dev 环境首次解析该依赖**需要联网**。

### 2. dev 环境（runClient）加载 Curios

`build.gradle`：

```groovy
maven { url = "https://maven.theillusivec4.top/" }          // Curios 官方 maven
compileOnly("top.theillusivec4.curios:curios-neoforge:${curios_version}")
runtimeOnly("top.theillusivec4.curios:curios-neoforge:${curios_version}")
```

`gradle.properties`：`curios_version=9.2.3+1.21.1`（版本号取自上面的缓存元数据，确有其物）。
坐标写成 `curios-neoforge`（Curios 在 NeoForge 上的制品名）。

**离线不炸**：本工程的编译验收走 `tools/manual_compile.ps1`（javac + 本地缓存 jar，不读 Gradle 依赖），
且**所有 Curios 调用一律走反射**（见 §4），所以「缓存里没有 Curios jar」时
`COMPILE OK` 依旧成立；只有需要真正跑 `runClient`（要加载 Curios 本体）时才必须联网。

### 3. 快捷键：注册位置与触发链路

| 项 | 值 / 位置 |
| --- | --- |
| 类 | `client/TerminalKeybinds.java`（`@EventBusSubscriber(bus = MOD, value = CLIENT)`） |
| KeyMapping 注册 | MOD 总线 `RegisterKeyMappingsEvent#onRegisterKeyMappings` → `event.register(mapping)` |
| 默认键 | **G**（`GLFW_KEY_G`）：原版未占用；Create 的按键是 Alt / Ctrl 系；RS 自己的无线终端快捷键默认**不绑定**，故互不冲突；玩家可在按键绑定里随时改键 / 解绑 |
| 类别 | `key.categories.rs_create_compat`（本模组自己的分类，中英双语）→ 原版「选项 → 控制 → 按键绑定」里单独一组 |
| 冲突上下文 | `KeyConflictContext.IN_GAME` —— 打开任意界面（含聊天栏 / 文本框输入）时该键**根本不激活** |
| 触发 | 游戏总线 `InputEvent.Key`（在 `ClientInit#onClientSetup` 里 `NeoForge.EVENT_BUS.addListener`），与 RS 原版快捷键同一条链路 |
| 额外条件 | `Minecraft.player != null` 且 `screen == null`（双保险）且**非旁观**；`while (consumeClick())` 一次按键只处理一次 |

**打开过程 = 复用 RS 原版链路（零新网络包、零新打开逻辑）**：

```java
RefinedStorageApi.INSTANCE.useSlotReferencedItem(player,
    normal, charged, creative);   // ① 客户端：找「唯一一个」终端引用
// ② 找到才发 RS 原生 C2S UseSlotReferencedItemPacket
// ③ 服务端 resolve 引用 → instanceof SlotReferenceHandlerItem → 调既有的
//    AbstractNetworkEnergyItem#use(ServerPlayer, ItemStack, SlotReference)
//    → AdvancedRemoteTerminalItem#openModeScreen（模式 / 电量 / 网络绑定全部照旧）
```

* **「是否持有终端」的判定**：由 RS 的 `CompositeSlotReferenceProvider#findForUse` 做 ——
  「背包（RS 自带 `InventorySlotReferenceProvider`）+ 各模组注册的 `SlotReferenceProvider`」，
  结果必须**恰好一个**：0 个 → 不打开，RS 原版红字短提示
  `item.refinedstorage.network_item.cannot_open_because_not_found`（"There isn't any %s in your inventory."）；
  ≥2 个 → 不打开，改提示 "...more than one..."。两种提示都是 RS 自带文案，不新增语言键。
* **服务端仍然是最终裁判**：包到服务端后重新解引用 + `instanceof` 校验物品类型，客户端伪造不了。

### 4. 饰品槽里的终端也要能被快捷键找到（`support/RsccCuriosTerminalSlot.java`）

本模组上一轮注册了自己的饰品槽 `rs_create_compat_curios_integration`（数据包 JSON）。
RS 原版只认识「背包 + 别的模组注册的提供者」，因此这里按 RS 的**官方扩展点**补一个提供者：

* `RefinedStorageApi#getSlotReferenceFactoryRegistry().register(id, factory)` —— 引用要能被
  `SlotReferenceFactory.STREAM_CODEC` 序列化才能随 C2S 包发给服务端（双端都要注册）；
* `RefinedStorageApi#addSlotReferenceProvider(provider)` —— 列出「本模组饰品槽里装着终端的格子」；
* 引用只存**槽位下标**，`resolve(player)` 时按同一槽位重新取**存活栈**
  （模式写回 / 电量消耗作用在这份实体上，与 RS 原版 `InventorySlotReference` 一致）；
  下标越界或该格已空 → `Optional.empty()`，服务端自然拒绝打开。
* `isDisabledSlot` 返回 `false`：饰品槽不在容器菜单里，没有需要禁用的菜单槽位。

**为什么全程反射**：本地缓存没有 Curios 1.21.1 的 jar，编译期引用其类会直接编译失败。
反射链：`CuriosApi.getCuriosInventory(LivingEntity)` → `ICuriosItemHandler#getStacksHandler(String)`
→ `ICurioStackHandler#getStacks()`（回退 `getSlots()` + `getStackInSlot(int)`）；
方法句柄按「实例类型自省」查找并缓存（Curios 各版本把接口类名改过名，故不写死类名）。
Curios 缺失 / 方法名不符 → 整条链路**静默降级为「只认背包」**并打一条 warn，绝不抛异常、绝不影响加载。
（顺带好处：终端戴在饰品槽里时，模式切换 Tab 的 `SwitchTerminalModePacket` 也能正常回写物品了。）

### 5. 语言键（走 frag，幂等）

`tools/gen_lang_frag_keybind.py` → `tools/lang_frag_keybind.json` → `tools/apply_lang_frag.py`：

| 键 | en | zh |
| --- | --- | --- |
| `key.categories.rs_create_compat` | RS & Create Compat | RS × 机械动力兼容 |
| `key.rs_create_compat.open_advanced_remote_terminal` | Open Advanced Remote Terminal | 打开高级远程终端 |

### 6. 本轮新增 / 修改文件

新增：`client/TerminalKeybinds.java`、`support/RsccCuriosTerminalSlot.java`、
`tools/gen_lang_frag_keybind.py` + `tools/lang_frag_keybind.json`、本追加脚本。

修改：`src/main/templates/META-INF/neoforge.mods.toml`（Curios = required / AFTER）、
`build.gradle`（Curios maven + compileOnly/runtimeOnly）、`gradle.properties`（`curios_version`）、
`client/ClientInit.java`（注册游戏总线 `InputEvent.Key` 监听）、
`support/OptionalDeps.java`（`MOD_CURIOS` + `isCuriosLoaded()`）、
`RS_Create_Compat.java`（commonSetup 里注册饰品槽引用来源）、
`assets/rs_create_compat/lang/{zh_cn,en_us}.json`（经 `apply_lang_frag.py`）。

**刻意未动**：既有 Curios 数据包（`data/rs_create_compat/curios/**`、`data/curios/tags/item/**`）、
单元样板管理器 / SPT、红石控制 / 保持器贴图、扳手 / 回流总线、执行舱模型 / 贴图相关文件，
以及任何 GUI 布局与贴图（本轮不涉及界面）。

### 7. 验证

* `tools/manual_compile.ps1` → `COMPILE OK (223 sources)`；
* `tools/verify_mixin_shadows.py` → 0 问题、退出码 0（本轮未新增 Mixin）；
* `tmp_textures/verify_gui_layout.py` / `tools/audit_gui_textures.py` / `tools/audit_shared_textures.py` → 各 0 项；
* `tools/verify_recipes.py` → 通过（未改配方）；
* `tools/apply_lang_frag.py` 重跑 → 新增 0 / 覆盖 0（幂等）。
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
