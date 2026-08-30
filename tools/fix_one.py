# -*- coding: utf-8 -*-
path = r"D:\MODS\refined_storage_and_create_compat\src\main\java\cretae\cookiewyq\rs_create_compat\client\screen\AdvancedSchematicLoaderScreen.java"
with open(path, 'r', encoding='utf-8') as f:
    src = f.read()
old = '        guiGraphics.drawString(font, playerInventoryTitle, 8, 204, 0xA0A0A0, false);'
new = '        guiGraphics.drawString(font, playerInventoryTitle, 8, 222, 0xA0A0A0, false);'
if old in src:
    src = src.replace(old, new)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(src)
    print("OK fixed")
else:
    print("WARN not found")
