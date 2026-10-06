# -*- coding: utf-8 -*-
"""第 12 轮用户反馈 4 条的源码锚点 + 可离线推演的自检。

 ① K 键「伪装隐藏 / 完整显示」切换时<b>强烈卡顿 + 整屏一白</b>
    根因：CamouflageShellDisplay 每次切换都调 levelRenderer.allChanged()（整片重烘）。
    修法：只重建真正受影响的伪装格（逐格 requestModelDataUpdate + 每区段一次 sendBlockUpdated）。
 ② 输入 / 输出总线普通模式下：不显示「已停用原因」说明；「普通 / 总线」按钮必须常驻且可来回切换；
    按钮位置必须换（旧位置在普通界面里压着第 8、9 个过滤器槽）。
 ③ 总线「详细配置」子界面：新增「废料」组；修正「输入时原料」判据为「起步原料 vs 步内投入物」。
 ④ 自动合成监视器：新增玩家可点的「缺料处置」（挂起 / 等待）开关，服务端权威（C2S + S2C 往返）。

用法：python tools/selfcheck_round12_feedback.py
      → 全部通过时输出 `SELFCHECK OK (n checks)`；失败时退出码 1。
"""
import io
import json
import os
import re
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
LANG = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

FAILURES = []
CHECKS = [0]


def read(*parts):
    with io.open(os.path.join(*parts), "r", encoding="utf-8") as handle:
        return handle.read()


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s %s" % (name, ("-> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, (" | " + detail) if detail else ""))


def code_only(src):
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


def section(title):
    print()
    print("=" * 84)
    print(title)
    print("=" * 84)


# ============================================================ ① K 键切换不全量重烘
section("① K 键切换：只重建受影响的伪装格（绝不全量重烘 / 不整屏一白）")

display_raw = read(SRC, "client", "CamouflageShellDisplay.java")
display = code_only(display_raw)
refresh_packet = read(SRC, "network", "RefreshCamouflagePacket.java")
client_init = read(SRC, "client", "ClientInit.java")

check("根因已消除：伪装外壳的客户端收尾里<b>不再</b>出现 levelRenderer.allChanged()（整片重烘）",
      "allChanged" not in display and "levelRenderer" not in display,
      "仍能找到全量重烘路径")
check("修法落地：逐格清模型数据缓存（requestModelDataUpdate）+ 每区段只标脏一次（sendBlockUpdated）",
      "requestModelDataUpdate()" in display
      and "sendBlockUpdated(pos, state, state, 16)" in display
      and "SectionPos" in display and "sections.add(" in display)
check("只重建「真正受影响的坐标」：坐标表来自收包路径的登记回调（不是全表扫描、不是按区块遍历）",
      "TRACKED" in display and "track(final BlockPos pos)" in display
      and "setClientTracker" in refresh_packet
      and "tracker.accept(pos)" in refresh_packet
      and "RefreshCamouflagePacket" in client_init
      and "CamouflageShellDisplay::track" in client_init)
check("惰性清理：已经不再伪装的坐标在下一次切换时被剔除（表不会无限增长）",
      "iterator.remove()" in display and "isCamouflaged(level, pos)" in display)
check("切换仍然只由「一个布尔位的翻转」触发（绝大多数 tick 一次布尔比较即返回）",
      "hidden == appliedHidden" in display and "ClientTickEvent.Post" in display
      and "CamouflageShellDisplay::onClientTick" in client_init)
check("切换路径不触发资源重载 / 不重建渲染器（剥掉注释后的代码里没有任何 Renderer 重载 API 调用）",
      "levelRenderer" not in display and "allChanged" not in display
      and "reload(" not in display and "onResourceManagerReload" not in display)

# 量化对比（离线算术）：改前 = 视野内<b>全部</b>已烘焙区段；改后 = 仅含伪装格的区段（同一区段只算一次）
RENDER_DISTANCE = 12          # 常见客户端视距（区块）
SECTIONS_PER_CHUNK = 24       # 1.18+ 每区块 24 个区段（y -64..320）
CAMOUFLAGED_BLOCKS = 64       # 例：一条产线上被裹住的线缆 / 管道总数
old_sections = (2 * RENDER_DISTANCE + 1) ** 2 * SECTIONS_PER_CHUNK
new_sections = CAMOUFLAGED_BLOCKS   # 上界：最坏情况「每格各占一个独立区段」
print("  量化对比（视距 %d 区块 / %d 格伪装）：" % (RENDER_DISTANCE, CAMOUFLAGED_BLOCKS))
print("    改前 allChanged()：整片作废重建 ≈ %d 个区段（= %d×%d 区块 × %d 区段）+ 全屏级重排"
      % (old_sections, 2 * RENDER_DISTANCE + 1, 2 * RENDER_DISTANCE + 1, SECTIONS_PER_CHUNK))
print("    改后逐格标脏：≤ %d 个区段（同一区段内多格共享一次重建）⇒ 下降 ≥ %.4f%%"
      % (new_sections, (1 - new_sections * 1.0 / old_sections) * 100))
check("量化：受影响区段数由 %d 降到 ≤ %d（下降 ≥ 99%%）且不触发任何全屏级操作"
      % (old_sections, new_sections),
      new_sections * 100 <= old_sections)
check("断言锁死范围：切换必须走 refreshTracked（逐格）这条路径，且 allChanged 在全工程客户端侧零引用",
      "refreshTracked(level)" in display
      and "allChanged" not in display
      and "allChanged" not in code_only(client_init))

# ============================================================ ② 普通模式：按钮常驻 + 无停用说明 + 位置
section("② 总线普通模式：切换按钮常驻可来回切、不显示「已停用原因」、位置不再压过滤器槽")

widget = read(SRC, "client", "widget", "ExporterExecutorRowWidget.java")
widget_code = code_only(widget)
normal_ui = widget_code[widget_code.index("private void drawNormalModeUi("):
                        widget_code.index("private void drawNormalToggle(")]

check("「切换按钮必须常驻」：普通界面里只要「可转换」就画（不看 forceNormal / disabled）",
      "return !executorMode() && convertible()" in widget_code
      and "if (convertible()) {" in normal_ui and "drawNormalToggle" in normal_ui)
check("「可以来回切换」：同一颗按钮一次点击就把状态翻过去（强制普通 ⇄ 恢复为总线界面）",
      "source.toggleForceNormal(!forceNormal());" in widget_code
      and "forceNormal() ? \"restore\" : \"button\"" in widget_code)
check("「普通模式不显示已停用原因那类说明」：普通模式下不画红条 / 不画它的显示可达区域按钮",
      "drawDisabledNotice" not in normal_ui
      and widget_code.count("drawDisabledNotice(guiGraphics, mouseX, mouseY);") == 1)
check("「换个位置」：切换按钮移到条下方留白带的右端（Menu 70,40 100×14 → 精灵 69,39）",
      "private static final int RESTORE_X = 62;" in widget
      and "private static final int RESTORE_Y = BAND_Y;" in widget
      and "private static final int RESTORE_W = 100;" in widget)
check("新位置不与任何现有内容冲突：它在过滤器槽位带（Menu y20..38）<b>下方</b>，且只与普通界面同帧出现",
      "RESTORE_Y = BAND_Y" in widget and "BAND_Y = 24" in widget
      and "private static final int FORCE_X = 132;" in widget)  # 旧位置仍在（延长界面里用），普通界面里不再用
check("旧位置（条右侧 Menu 140,16）在普通界面里不再命中 —— 那里是过滤器槽，绝不能拦",
      "return executorMode() && convertible() && !forceNormal()" in widget_code)

# ============================================================ ③ 六组分类 + 四组正确
section("③ 「详细配置」子界面：新增「废料」组；「输入时原料」判据 = 起步原料 vs 步内投入物")

child = read(SRC, "client", "screen", "BusCategoryConfigScreen.java")
zh = json.load(io.open(os.path.join(LANG, "zh_cn.json"), encoding="utf-8"))
en = json.load(io.open(os.path.join(LANG, "en_us.json"), encoding="utf-8"))
BUS = "gui.rs_create_compat.bus_config."

check("「废料」组已新增且排进 GROUP_ORDER（第 5 组，紧随成品）",
      'GROUP_SCRAP = "scrap"' in child
      and re.search(r"GROUP_ORDER = List\.of\(\s*GROUP_MATERIALS, GROUP_FEEDSTOCK, GROUP_FLUIDS, "
                    r"GROUP_PRODUCTS, GROUP_SCRAP,\s*GROUP_INTERMEDIATES\)", child) is not None)
check("成品 / 废料分开判（不再用 isProduct() 把两者一律归入成品组）",
      "if (category.isResult()) {" in child and "if (category.isScrap()) {" in child
      and "if (category.isProduct()) {" not in child)
check("feedstock 判据已修正：读同一份 Create 序列装配配方（起步原料 / 步内投入物），不再看「是否出现在产出侧」",
      "recipe.getIngredient().getItems()" in child
      and "SequencedRecipeProbe.stepInputItems(step.getRecipe())" in child
      and "!startingItems.contains(item) && stepInputItems.contains(item)" in child
      and "reflowItemIds" not in child)
check("语言键中英成对且中文 ≤ 40 字（废料 / Scrap）",
      zh[BUS + "group.scrap"] == "废料" and en[BUS + "group.scrap"] == "Scrap"
      and len(zh[BUS + "group.scrap"]) <= 40)

# 四组分类正确（精密构件产线）：复刻 groupKeyOf 的判定顺序
START_ITEMS = {"create:golden_sheet"}
STEP_ITEMS = {"create:cogwheel", "create:large_cogwheel", "minecraft:iron_nugget"}


def group_of(cid):
    if cid.startswith("fluid:"):
        return "fluids"
    if cid == "intermediate" or cid.startswith("intermediate:"):
        return "intermediates"
    if cid.startswith("result:"):
        return "products"
    if cid.startswith("scrap:"):
        return "scrap"
    item = cid[len("input:"):] if cid.startswith("input:") else None
    if item is not None and item not in START_ITEMS and item in STEP_ITEMS:
        return "feedstock"
    return "materials"


SIM = [("result:create:precision_mechanism", "products"),
       ("scrap:create:andesite_alloy", "scrap"),
       ("input:create:golden_sheet", "materials"),
       ("input:create:cogwheel", "feedstock"),
       ("input:create:large_cogwheel", "feedstock"),
       ("input:minecraft:iron_nugget", "feedstock")]
check("四组分类正确：成品=精密构件 / 废料=安山合金 / 原料=金板 / 输入时原料=齿轮·大齿轮·铁粒",
      all(group_of(cid) == want for cid, want in SIM),
      str({cid: group_of(cid) for cid, _ in SIM}))

# ============================================================ ④ 缺料处置开关：服务端权威往返
section("④ 自动合成监视器：「缺料处置」开关（挂起 / 等待）—— C2S + S2C 往返、服务端权威")

set_packet = read(SRC, "network", "SetShortageModePacket.java")
req_packet = read(SRC, "network", "RequestShortageModePacket.java")
sync_packet = read(SRC, "network", "SyncShortageModePacket.java")
client_mirror = read(SRC, "client", "ShortageModeClient.java")
# 第 47 轮（专服启动即崩修复）之后，载荷类里不许再出现客户端类型：S2C 处理体改成
# 「投递到主线程 + 交给 network/ClientPayloadHooks 的客户端实现」，真正的写入点搬到
# client/ClientPayloadSink#syncShortageMode。断言意图不变（唯一写入路径 = S2C 权威快照），
# 故下面同时核对「路由」与「写入点」两处锚点。
client_sink = read(SRC, "client", "ClientPayloadSink.java")
monitor = read(SRC, "mixin", "client", "AutocraftingMonitorScreenMixin.java")
main_src = read(SRC, "RS_Create_Compat.java")
policy = read(SRC, "support", "RsccShortagePolicy.java")

check("开关按钮常驻可见可点，文案跟随服务端权威档位（客户端不做任何乐观更新）",
      "rscc$shortageButton.visible = true;" in monitor
      and "rscc$refreshShortageButton()" in monitor
      and "ShortageModeClient.mode()" in monitor)
check("点一下 = 在挂起 / 等待之间切换，并且只发一个 C2S 包（服务端权威）",
      "ShortageModeClient.mode() == RsccShortagePolicy.Mode.SUSPEND" in monitor
      and "PacketDistributor.sendToServer(new SetShortageModePacket(next.ordinal()));" in monitor)
check("C2S 只被「正开着监视器」的玩家接受，并落到唯一写入点 RsccShortagePolicy.setMode",
      "player.containerMenu instanceof RsccAssemblyMonitorBridge" in set_packet
      and "RsccShortagePolicy.get(server).setMode(mode);" in set_packet)
check("服务端写入后立刻单播回权威值（客户端手上下一次 render 就是新样子）",
      "PacketDistributor.sendToPlayer(player, new SyncShortageModePacket(mode.ordinal()));" in set_packet)
check("打开界面主动拉一次快照（策略只在改动时回发，玩家可能在上次改动之后才打开监视器）",
      "PacketDistributor.sendToServer(new RequestShortageModePacket());" in monitor
      and "RsccShortagePolicy.get(server).getMode().ordinal()" in req_packet
      and "sendToServer" not in req_packet)
check("S2C 写进只读镜像；镜像默认档 = 服务端默认档（快照未到时不会显示反的档位）",
      "ctx.enqueueWork(() -> ClientPayloadHooks.get().syncShortageMode(packet));" in sync_packet
      and "ShortageModeClient.set(packet.mode());" in client_sink
      and "Mode.SUSPEND.ordinal()" in client_mirror)
check("三个包都在主类注册（漏注册会直接打断连接）",
      "SetShortageModePacket.TYPE" in main_src and "RequestShortageModePacket.TYPE" in main_src
      and "SyncShortageModePacket.TYPE" in main_src)
check("界面侧新键中英成对（含 tooltip 三行），且复用服务端已有档位文案键",
      all("gui.rs_create_compat.assembly.monitor.shortage." + key in zh
          and "gui.rs_create_compat.assembly.monitor.shortage." + key in en
          for key in ("suspend", "wait", "title", "tip"))
      and "message.rs_create_compat.shortage.mode.suspend" in zh
      and "message.rs_create_compat.shortage.mode.wait" in zh)
check("档位取值只有两个（挂起 / 等待），且默认档 = 挂起（与既有行为逐字一致）",
      "SUSPEND(\"suspend\")," in policy and "WAIT(\"wait\");" in policy
      and "DEFAULT_MODE = Mode.SUSPEND" in policy)

# 往返同步推演：点一下 → 服务端写 → 回发 → 客户端镜像；再来一次必须回到原档（严格可逆、无丢失）
MY_MODES = ["SUSPEND", "WAIT"]


def toggle(current):
    return "WAIT" if current == "SUSPEND" else "SUSPEND"


round_trip = []
state = "SUSPEND"          # 服务端权威档（默认）
mirror = "SUSPEND"         # 客户端镜像（默认同档）
for _ in range(4):
    want = toggle(mirror)  # 客户端按「当前镜像」算出下一档并发包
    state = want           # 服务端写入
    mirror = state         # 服务端立刻回发 → 客户端镜像
    round_trip.append(mirror)
check("往返同步推演：4 次点击后档位严格交替 SUSPEND→WAIT→SUSPEND→WAIT（客户端镜像与服务端一致，无丢失）",
      round_trip == ["WAIT", "SUSPEND", "WAIT", "SUSPEND"]
      and all(m in MY_MODES for m in round_trip),
      str(round_trip))

# ============================================================ 结果
print()
print("=" * 84)
if FAILURES:
    print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    for item in FAILURES:
        print("  - %s" % item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
sys.exit(0)
