package cretae.cookiewyq.rs_create_compat.support;

/**
 * 「幽灵（标记）内容」标记接口：实现它的容器<b>永远不参与任何掉落 / 回网结算</b>。
 *
 * <h2>为什么需要它</h2>
 * 部分机器为了方便玩家标记「这一步吃/出什么」，会在界面上摆出一些<b>只是模板</b>的容器
 * （例：序列装配样板终端的 {@code ingredientSlot} 输入原料标记、{@code resultSlots} 产物标记、
 * {@code scrapSlots} 废料标记）。这些格子里的物品是生成逻辑<b>复制进来看的</b>，
 * 玩家从未真正投入过它们；一旦被当成真实资源结算（爆出 / 写回网络 / 存方块 NBT），
 * 就等价于<b>凭空复制物品</b>。
 *
 * <h2>两道防线</h2>
 * <ol>
 *     <li>机器自己提供「可掉落容器」白名单（例：{@code getDroppableHandlers()}），
 *     幽灵容器根本不在其中；</li>
 *     <li>即使将来有人误把幽灵容器加进掉落清单，{@link BlockContentReleaser} 的收集入口
 *     （{@link BlockContentReleaser#collectHandler} / {@link BlockContentReleaser#collectContainer}）
 *     也会按本接口<b>统一跳过</b>，从这个入口进来的一切收集都不会再复现该 bug。</li>
 * </ol>
 *
 * <p>注意：实现本接口<b>只</b>影响「破坏掉落」结算；容器的 NBT 存档 / 读档完全照旧，
 * 因此界面重开后这些标记仍能原样恢复显示。</p>
 */
public interface GhostContent {
}
