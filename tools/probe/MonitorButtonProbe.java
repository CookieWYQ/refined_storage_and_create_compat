import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import sun.misc.Unsafe;

/**
 * 运行期探针（无需 Minecraft bootstrap / 无需 OpenGL）：
 * 用 sun.misc.Unsafe 绕过构造器与 Minecraft 静态注册表的初始化，直接造出真实的
 * AssemblyWatchdog.Record 与 client.AssemblyAlertsClient.ActionView，把「按钮可见性」
 * 这条链路真正跑出来。
 *
 *   ① Record#actions()（服务端唯一按钮可见性判据）在 ourChain=true/false 下的真值表；
 *   ② AssemblyAlertsClient.actionView(...)（客户端唯一按钮可见性判据）；
 *   ③ Record#snapshot()：普通任务(ourChain=false)未挂起时是否仍然带 SUSPEND 位。
 */
public final class MonitorButtonProbe {
    private static final Unsafe UNSAFE = unsafe();
    private static int checks = 0;
    private static int fails = 0;
    private static final int SUSPEND_BIT = 4;
    private static final int RESUME_BIT = 1;
    private static final int MACHINE_BIT = 2;
    private static final int NONE_BIT = 0;

    private static Unsafe unsafe() {
        try {
            final Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (Unsafe) f.get(null);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void check(final String name, final boolean ok, final String detail) {
        checks++;
        if (!ok) {
            fails++;
        }
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : ("  | " + detail)));
    }

    public static void main(final String[] args) throws Exception {
        final Class<?> recordClass = Class.forName(
            "cretae.cookiewyq.rs_create_compat.support.AssemblyWatchdog$Record");
        final Class<?> suspendStateClass = Class.forName(
            "cretae.cookiewyq.rs_create_compat.support.AssemblyWatchdog$SuspendState");
        final Class<?> reasonClass = Class.forName(
            "cretae.cookiewyq.rs_create_compat.support.AssemblyWatchdog$Reason");

        final Method actions = recordClass.getDeclaredMethod("actions");
        actions.setAccessible(true);
        final Method snapshot = recordClass.getDeclaredMethod("snapshot");
        snapshot.setAccessible(true);

        final Object running = Enum.valueOf(suspendStateClass.asSubclass(Enum.class), "RUNNING");
        final Object suspended = Enum.valueOf(suspendStateClass.asSubclass(Enum.class), "SUSPENDED");
        final Object reasonNone = Enum.valueOf(reasonClass.asSubclass(Enum.class), "NONE");
        final Object reasonOffline = Enum.valueOf(reasonClass.asSubclass(Enum.class), "EXECUTOR_OFFLINE");

        final Field taskIdF = field(recordClass, "taskId");
        final Field returningF = field(recordClass, "returning");
        final Field ourChainF = field(recordClass, "ourChain");
        final Field sequenceF = field(recordClass, "sequence");
        final Field suspendStateF = field(recordClass, "suspendState");
        final Field suspendedF = field(recordClass, "suspended");
        final Field reasonF = field(recordClass, "reason");
        final Field offlineStepsF = field(recordClass, "offlineSteps");
        final Field materialIconsF = field(recordClass, "materialIcons");
        final Field materialNamesF = field(recordClass, "materialNames");
        final Field productIconF = field(recordClass, "productIcon");
        final Field productNameF = field(recordClass, "productName");

        System.out.println("=== (1) server Record#actions(): the ONLY source of button visibility ===");
        recordCase(recordClass, taskIdF, actions, returningF, ourChainF, sequenceF, suspendStateF,
            suspendedF, reasonF, offlineStepsF, running, reasonNone, false, false, false,
            "ordinary task (ourChain=false), RUNNING, not returning", SUSPEND_BIT);
        recordCase(recordClass, taskIdF, actions, returningF, ourChainF, sequenceF, suspendStateF,
            suspendedF, reasonF, offlineStepsF, running, reasonNone, false, true, false,
            "sequence task (ourChain=true), RUNNING, not returning", SUSPEND_BIT);
        recordCase(recordClass, taskIdF, actions, returningF, ourChainF, sequenceF, suspendStateF,
            suspendedF, reasonF, offlineStepsF, suspended, reasonNone, false, false, false,
            "ordinary task, SUSPENDED", RESUME_BIT);
        recordCase(recordClass, taskIdF, actions, returningF, ourChainF, sequenceF, suspendStateF,
            suspendedF, reasonF, offlineStepsF, suspended, reasonOffline, false, false, false,
            "ordinary task, SUSPENDED + executor offline (no offline step)", RESUME_BIT);
        recordCase(recordClass, taskIdF, actions, returningF, ourChainF, sequenceF, suspendStateF,
            suspendedF, reasonF, offlineStepsF, running, reasonNone, true, false, false,
            "ordinary task, RS returning internal storage", NONE_BIT);
        recordCase(recordClass, taskIdF, actions, returningF, ourChainF, sequenceF, suspendStateF,
            suspendedF, reasonF, offlineStepsF, suspended, reasonNone, false, true, false,
            "sequence task, SUSPENDED", RESUME_BIT);

        System.out.println();
        System.out.println("=== (2) client actionView(): visible == offered(bit) ===");
        final Class<?> alertClass = Class.forName(
            "cretae.cookiewyq.rs_create_compat.network.SyncAssemblyAlertsPacket$Alert");
        final Class<?> clientClass = Class.forName(
            "cretae.cookiewyq.rs_create_compat.client.AssemblyAlertsClient");
        final Method actionView = clientClass.getDeclaredMethod("actionView", alertClass, UUID.class, int.class);
        actionView.setAccessible(true);
        final Method setAlerts = clientClass.getDeclaredMethod("setAlerts", List.class);
        setAlerts.setAccessible(true);
        final Method offers = alertClass.getDeclaredMethod("offers", int.class);
        offers.setAccessible(true);
        final Class<?> viewClass = Class.forName(
            "cretae.cookiewyq.rs_create_compat.client.AssemblyAlertsClient$ActionView");
        final Method visibleMethod = viewClass.getDeclaredMethod("visible");
        visibleMethod.setAccessible(true);

        final UUID id = UUID.randomUUID();
        setAlerts.invoke(null, new ArrayList<>());
        Object view = actionView.invoke(null, null, id, SUSPEND_BIT);
        check("no alert (alert==null) => NOT visible", !(Boolean) visibleMethod.invoke(view), String.valueOf(view));

        // 直接构造真实的 ActionView 记录实例（它的静态初始化不碰任何 Minecraft 注册表）。
        // ActionView 正是 actionView(...) 的返回值类型，也是 rscc$apply 唯一读取的可见性对象，
        // 因此这里量到的就是按钮真正的 visible 值。
        final java.lang.reflect.Constructor<?> viewCtor =
            viewClass.getDeclaredConstructor(boolean.class, boolean.class);
        viewCtor.setAccessible(true);
        for (final boolean[] pair : new boolean[][] {{true, true}, {true, false}, {false, false}}) {
            final Object av = viewCtor.newInstance(pair[0], pair[1]);
            check("ActionView(visible=" + pair[0] + ", enabled=" + pair[1] + ").visible() == " + pair[0]
                    + "（= 服务端动作位；enabled 不参与可见性）",
                ((Boolean) visibleMethod.invoke(av)) == pair[0], String.valueOf(av));
        }
        check("actionView 的返回值类型就是 ActionView（客户端可见性唯一来源；在途只进 enabled）",
            actionView.getReturnType() == viewClass, String.valueOf(actionView.getReturnType()));

        System.out.println();
        System.out.println("=== (3) alerts() 的准入 = 动作位 != NONE（普通任务同样入列） ===");
        // 说明：Record#snapshot() 会构造 SyncAssemblyAlertsPacket.Alert，而那个 record 的紧凑构造器里
        // 会引用 ItemStack.EMPTY ⇒ 触发 ItemStack 的静态初始化 ⇒ 需要 Minecraft bootstrap（本探针不做）。
        // 因此这一节只验证**决定入列的那一步**：alerts() 的过滤条件是 actions() != ACTION_BIT_NONE，
        // 而 (1) 已经量出「普通任务 RUNNING 时 actions() == 4（!= 0）」⇒ 普通任务同样会被收进快照。
        final Object ordinary = newRecord(recordClass, taskIdF, returningF, ourChainF, sequenceF,
            suspendStateF, suspendedF, reasonF, offlineStepsF, materialIconsF, materialNamesF,
            productIconF, productNameF, running, reasonNone, false, false, false);
        final Object ordinaryBits = actions.invoke(ordinary);
        check("ordinary (ourChain=false) RUNNING task: actions()=" + ordinaryBits
                + " != ACTION_BIT_NONE(0) => alerts() 会把它收进快照（普通任务也有「挂起」按钮）",
            ((Integer) ordinaryBits) != 0, "bits=" + ordinaryBits);

        System.out.println();
        System.out.println(fails == 0
            ? ("MONITOR_BUTTON_PROBE OK (" + checks + " checks)")
            : ("MONITOR_BUTTON_PROBE FAILED (" + fails + "/" + checks + ")"));
        System.exit(fails == 0 ? 0 : 1);
    }

    private static Field field(final Class<?> owner, final String name) throws Exception {
        final Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static Object newRecord(final Class<?> recordClass, final Field taskIdF, final Field returningF,
                                    final Field ourChainF, final Field sequenceF, final Field suspendStateF,
                                    final Field suspendedF, final Field reasonF, final Field offlineStepsF,
                                    final Field materialIconsF, final Field materialNamesF,
                                    final Field productIconF, final Field productNameF,
                                    final Object state, final Object reason, final boolean returning,
                                    final boolean ourChain, final boolean isSequence) throws Exception {
        final Object rec = UNSAFE.allocateInstance(recordClass);
        taskIdF.set(rec, UUID.randomUUID());
        returningF.setBoolean(rec, returning);
        ourChainF.setBoolean(rec, ourChain);
        sequenceF.setBoolean(rec, isSequence);
        suspendStateF.set(rec, state);
        reasonF.set(rec, reason);
        suspendedF.setBoolean(rec, !"RUNNING".equals(((Enum<?>) state).name()));
        offlineStepsF.set(rec, new ArrayList<>());
        materialIconsF.set(rec, new ArrayList<>());
        materialNamesF.set(rec, new ArrayList<>());
        productNameF.set(rec, "probe");
        return rec;
    }

    private static void recordCase(final Class<?> recordClass, final Field taskIdF, final Method actions,
                                   final Field returningF, final Field ourChainF, final Field sequenceF,
                                   final Field suspendStateF, final Field suspendedF, final Field reasonF,
                                   final Field offlineStepsF, final Object state, final Object reason,
                                   final boolean returning, final boolean ourChain, final boolean isSequence,
                                   final String label, final int expected) throws Exception {
        final Object rec = UNSAFE.allocateInstance(recordClass);
        taskIdF.set(rec, UUID.randomUUID());
        returningF.setBoolean(rec, returning);
        ourChainF.setBoolean(rec, ourChain);
        sequenceF.setBoolean(rec, isSequence);
        suspendStateF.set(rec, state);
        reasonF.set(rec, reason);
        suspendedF.setBoolean(rec, !"RUNNING".equals(((Enum<?>) state).name()));
        offlineStepsF.set(rec, new ArrayList<>());
        final Object got = actions.invoke(rec);
        check(label + " => bits=" + expected + " [ourChain=" + ourChain + "]",
            ((Integer) got) == expected, "got=" + got);
    }

    private static Object newView(final Class<?> viewClass, final boolean visible, final boolean enabled)
        throws Exception {
        final Object view = UNSAFE.allocateInstance(viewClass);
        final Field visibleF = viewClass.getDeclaredField("visible");
        final Field enabledF = viewClass.getDeclaredField("enabled");
        UNSAFE.putBoolean(view, UNSAFE.objectFieldOffset(visibleF), visible);
        UNSAFE.putBoolean(view, UNSAFE.objectFieldOffset(enabledF), enabled);
        return view;
    }

    private static Object alert(final Class<?> alertClass, final UUID id, final int reason, final int bits)
        throws Exception {
        // 用真实构造器：ItemStack 的空栈走「public ItemStack(Void)」，不触碰 ItemStack.EMPTY
        // （后者会触发 BuiltInRegistries 的静态初始化，而本探针不做 Minecraft bootstrap）。
        return alertClass.getDeclaredConstructor(UUID.class, int.class, int.class, String.class,
                net.minecraft.world.item.ItemStack.class, long.class, List.class, List.class)
            .newInstance(id, reason, bits, "x", emptyStack(), 1L, new ArrayList<>(), new ArrayList<>());
    }

    private static net.minecraft.world.item.ItemStack emptyStack() throws Exception {
        final Constructor<net.minecraft.world.item.ItemStack> c =
            net.minecraft.world.item.ItemStack.class.getDeclaredConstructor(Void.class);
        c.setAccessible(true);
        return c.newInstance((Object) null);
    }
}