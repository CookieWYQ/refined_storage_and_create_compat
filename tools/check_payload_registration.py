# -*- coding: utf-8 -*-
"""检查「net/network 包里每个自定义包是否都在 RS_Create_Compat.registerPayloads 里注册了」。

为什么需要这个检查：漏注册的包一旦被发送，NeoForge 会抛
    UnsupportedOperationException: Payload <id> may not be sent to the client!
并直接把连接打断（现象可能是「无法进入单人存档 / 与服务器断开连接」）。
本脚本把「定义了 TYPE 的包类」与「被 registrar 注册过的类名」做差集。

用法：python tools/check_payload_registration.py
退出码：0 = 全部已注册；1 = 存在未注册的包（打印清单）。
"""
import io
import os
import re
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NET_DIR = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat", "network")
MAIN = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat", "RS_Create_Compat.java")

# 包类特征：声明了 TYPE 常量。两种常见写法都要认：
#   public static final CustomPacketPayload.Type<X> TYPE = ...
#   public static final Type<X> TYPE = ...              （Type 由 import 引入）
TYPE_DEF = re.compile(r"\b(?:CustomPacketPayload\s*\.\s*)?Type\s*<[^;=]{0,120}>\s+TYPE\s*=")


def main():
    if not os.path.isdir(NET_DIR) or not os.path.exists(MAIN):
        print("[MISS] 找不到 network 包或主类：%s" % (NET_DIR if not os.path.isdir(NET_DIR) else MAIN))
        return 1

    payloads = []
    for name in sorted(os.listdir(NET_DIR)):
        if not name.endswith(".java"):
            continue
        text = io.open(os.path.join(NET_DIR, name), encoding="utf-8").read()
        if TYPE_DEF.search(text):
            payloads.append(name[:-5])

    main_text = io.open(MAIN, encoding="utf-8").read()
    # 注册处引用形如 `xxx.FooBarPacket.TYPE` / `.STREAM_CODEC`；包类名不一定以 Packet 结尾
    # （例如 CompletionBannerPayload），所以这里收集所有「被 .TYPE/.STREAM_CODEC 引用过的类名」。
    referenced = set(re.findall(r"([A-Za-z0-9_]+)\s*\.\s*(?:TYPE|STREAM_CODEC)", main_text))
    registered = {name for name in referenced}

    missing = [p for p in payloads if p not in registered]
    print("[info] 自定义包 %d 个；主类引用了 %d 个包常量" % (len(payloads), len(registered)))
    if missing:
        print("[FAIL] 以下 %d 个包从未在主类注册（发送时会抛 may not be sent / 未注册）：" % len(missing))
        for p in missing:
            print("   - %s" % p)
        return 1
    print("[OK] 全部自定义包都已在 RS_Create_Compat.registerPayloads 注册")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
