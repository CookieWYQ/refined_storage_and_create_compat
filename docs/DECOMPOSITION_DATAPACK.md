# 降解室发电机：用数据包添加你自己的植物

本文对应方块 **`rs_create_compat:decomposition_generator`（降解室发电机）** 的数据包入口。
模组作者 / 整合包作者 / 玩家都可以用一份**数据包**（不是资源包）往里加自己的植物、改数值，
**不需要改模组代码、不需要重启游戏**（`/reload` 即可）。

> 目录名固定：`data/<你的命名空间>/decomposition/*.json`
> 模组自己的内置默认表也在同一位置（`data/rs_create_compat/decomposition/builtin_plants.json`，
> 以及 `farmers_delight.json`），可以当作最完整的示例来抄。

---

## 1. 最小可用示例

文件：`data/mypack/decomposition/my_plants.json`

```json
{
  "replace": false,
  "entries": [
    { "item": "mymod:glow_berry_bush", "time": 80, "energy": 6000 },
    { "item": "mymod:rubber_sapling", "time": 120, "energy": 9000 },
    { "tag": "mymod:plants", "time": 60, "energy": 3600 }
  ]
}
```

- `item`：物品 id（**方块物品也算**，例如 `minecraft:oak_sapling` 就是方块物品）。
- `tag`：物品标签，**不带 `#`**（写成 `"tag": "minecraft:flowers"`）。
- `time`：降解 **1 个** 该物品需要的 tick（20 tick = 1 秒），范围 `1 … 7200`。
- `energy`：降解完成时**一次性**写进机器内部缓存（FE），范围 `1 … 100000000`。
  平均功率 ≈ `energy / time`（例如 `8000 / 100` = 80 FE/t）。

放进数据包后 `/reload`，界面里对应的物品就能放进 256 格库存了。

---

## 2. 完整字段表

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `replace` | bool | 否 | `true` = **先清空**此前累积的所有条目（含模组内置表）再加本文件的条目；默认 `false` = 追加 |
| `mod_loaded` | string | 否 | 该 modId 没装时**整份文件跳过**。用它可以只给「装了 X 模组」的整合包加条目，避免日志里出现「id 不存在」的警告 |
| `remove` | string[] | 否 | 删掉已有条目：`"minecraft:oak_sapling"` 删精确物品，`"#minecraft:leaves"` 删标签条目（这里**要**带 `#`） |
| `entries` | object[] | 否 | 条目数组；每个条目必须恰好有 `item` 或 `tag` 之一，并且有 `time` + `energy` |

条目里 `item` 与 `tag` 同时出现时**以 `item` 为准**。

---

## 3. 覆盖 / 叠加规则（重要）

1. **文件顺序**：模组内置表（命名空间 `rs_create_compat`）**永远最先**应用；其余命名空间按字母序排在后面。
   所以「你的数据包覆盖内置表」不依赖文件名运气。
2. **同一条目后写覆盖先写**：同一个物品被声明多次时，**最后出现的赢**。
3. **`replace: true`** 会清空此前的一切（包括内置表）—— 这是「我要完全自定义整套植物表」的开关。
4. **`remove`** 在 `replace` 之后、`entries` 之前生效。
5. **精确 `item` 优先于 `tag`**：查表时先看精确物品，再按文件顺序看标签。

### 例子：把默认表里的原木…（本来就没有）换成只认我自己模组的植物

```json
{
  "replace": true,
  "entries": [
    { "tag": "mymod:all_plants", "time": 100, "energy": 8000 }
  ]
}
```

### 例子：只把「树叶」改便宜，其余保持默认

```json
{
  "replace": false,
  "entries": [
    { "tag": "minecraft:leaves", "time": 20, "energy": 1000 }
  ]
}
```

---

## 4. 容错（写错了不会炸服务器）

- 引用了**不存在的物品 id** ⇒ 跳过该条 + 日志一条警告，其余照常生效；
- 引用了**不存在的标签** ⇒ 不会报错（标签是查询时才匹配的，空标签 = 永远匹配不到）；
- `time` / `energy` 越界或缺失 ⇒ 跳过该条（不写进表）；
- 整个文件不是合法 JSON / 根节点不是对象 ⇒ 跳过该文件 + 警告；
- 重载结束后只打**一条**汇总日志：`files= applied= skipped= itemErrors= entryErrors= items= tags=`。

> 注：`mod_loaded` 的作用就是把「某个 mod 没装时的无效条目」提前挡在文件级，别让它们变成一堆 warning。

---

## 5. 数据包放在哪

单人生存：`.minecraft/saves/<存档名>/datapacks/<你的包>/`  
服务器：`<服务器目录>/world/datapacks/<你的包>/`

包内结构：

```
<pack>/pack.mcmeta
<pack>/data/mypack/decomposition/my_plants.json
```

`pack.mcmeta` 最小内容（`pack_format` 见你玩的版本，1.21.1 是 `48`）：

```json
{ "pack": { "pack_format": 48, "description": "my plants for the decomposition generator" } }
```

放好后 `/reload`（或重进世界）即可生效，**不需要重启**。

---

## 6. 与机器机制的对应关系（写数值前值得知道的三件事）

- **同一时间只处理一格**（像熔炉）：一台机器的总功率上限 ≈ `单条 energy / 单条 time`。
  想更快只有两条路：把条目数值调高，或多摆几台机器（256 格库存对单格处理是「排队」不是「并行」）。
- **缓存满 = 暂停**：内部缓存上限默认 100 万 FE（`Config.decompositionGeneratorCapacity`）。
  缓存装不下「下一次发电量」时机器会**暂停并且不消耗物品**（界面显示「缓存已满」），
  等电被网络/相邻方块取走再继续 —— 所以不会出现「电丢了还把植物吃了」。
  单次 `energy` 若被写成**大于缓存容量**，结算时会**夹到容量**（并打一条警告），
  于是「一次把缓存充满」，不会卡死；但这样发电效率就受缓存容量限制了，建议仍然把单次 `energy`
  控制在缓存容量以下。
- **只收表里的物品**：木头 / 石头 / 泥土这类不在表里的东西，输出总线 / 漏斗**推不进去**（`isItemValid` 拒绝），
  玩家手动放也会被拒绝并收到一句提示。

---

## 7. 内置默认表覆盖了什么

- **原版植物**：树叶（`#minecraft:leaves`）、花（`#minecraft:flowers`）、树苗（`#minecraft:saplings`）、
  可种植种子（`#minecraft:villager_plantable_seeds`），以及草 / 蕨 / 藤蔓 / 睡莲 / 海带 / 甘蔗 / 仙人掌 /
  竹子 / 蘑菇 / 下界植物 / 作物果实 / 南瓜西瓜干草块蘑菇块海带块苔藓块 …（完整清单见
  `data/rs_create_compat/decomposition/builtin_plants.json`）。
- **不含木头**：原木 / 木板 / 木棍 / 木质建筑方块**一个都没有**（用户要求「不包括原木之类的木头」）。
- **农夫乐事**（装了才生效，`mod_loaded` 门）：`#farmersdelight:wild_crops`、
  `#farmersdelight:mushroom_colonies`，以及白菜 / 番茄 / 洋葱 / 大米 / 稻穗 / 白菜叶 / 两种种子（按 id）。
