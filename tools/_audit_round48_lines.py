# -*- coding: utf-8 -*-
"""round48：打印改动点的准确行号（交付说明用）。"""
import io, os, sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
BASE = os.path.join("src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")


def find(rel, pats):
    ls = io.open(os.path.join(BASE, rel), encoding="utf-8").read().splitlines()
    for pat in pats:
        for i, l in enumerate(ls, 1):
            if pat in l:
                print("%-48s :%-6d %s" % (rel, i, l.strip()[:86]))
                break
        else:
            print("%-48s :?????  NOT FOUND %s" % (rel, pat))


find("Config.java", ['define("devLogs"', "public static boolean devLogs;", "devLogs = DEV_LOGS.get();",
                     "RsccAssemblyDebug.initFromConfig("])
find("support/RsccAssemblyDebug.java", ["private static volatile boolean enabled",
                                        "public static boolean changed(",
                                        "public static void transition(",
                                        "public static void warn(",
                                        "devLogs={} (source=config)"])
find("command/CompatCommands.java", ['Commands.literal("devlogs")', 'Commands.literal("assemblydebug")'])
find("block/entity/SchematicLoaderBlockEntity.java", ["protected void logState(",
                                                      "RsccAssemblyDebug.isEnabled()"])
find("block/entity/RangeChargerBlockEntity.java", ["[rscc-range-charger] save", "load-enter", "load-done"])
find("support/KeeperOverflow.java", ["开始销毁过量", "过量已清完", "停止销毁过量"])
find("support/RsccFlowLedger.java", ["balanced again", "unbalanced (first)",
                                     "same imbalance repeated", "unbalanced (sustained"])
find("support/RsccIntermediateFlow.java", ["[rscc-intermediate-flow] moved="])
find("network/IntermediateCacheNetworkNode.java", ["[rscc-cache-source] refreshSources"])
find("support/AssemblyWatchdog.java", ["final boolean devLogs", "bindingorphan@", "bindinggap@"])
find("support/RsccDiag.java", ["diag logging ON (default)"])
find("block/entity/SequenceExecutionChamberBlockEntity.java",
     ["final boolean nobody =", "stepOwnerMissingAt = level.getGameTime();",
      "RsccAssemblyDebug.warn(\"unowned@"])
print()
for f in ["support/RsccFlowLedger.java", "support/RsccIntermediateFlow.java",
          "network/IntermediateCacheNetworkNode.java", "support/AssemblyWatchdog.java",
          "support/RsccDiag.java", "block/entity/SchematicLoaderBlockEntity.java",
          "block/entity/RangeChargerBlockEntity.java", "support/KeeperOverflow.java"]:
    ls = io.open(os.path.join(BASE, f), encoding="utf-8").read().splitlines()
    hits = [i for i, l in enumerate(ls, 1) if "RsccAssemblyDebug.isEnabled()" in l]
    print("%-52s isEnabled() 行号: %s" % (f, hits))
