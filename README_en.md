# Create & Refined Storage: Compatibility and Improvements (rs_create_compat)

**Bridges Create's Sequenced Assembly into Refined Storage autocrafting: place an order in RS, let Sequence Execution Chambers drive Create machines step by step, and the products go back into the network.**

| Item | Version |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.248 (range `[21,)`) |
| Create | 6.0.10-280 (range `[6.0,)`) |
| Refined Storage 2 | 2.0.0 (range `[2.0.0,3.0.0)`) |
| Mod version | 1.1.0 |
| Mod ID | `rs_create_compat` |
| Author | CallMeACookieWYQ |
| License | All Rights Reserved |

> Chinese names quoted below follow this mod's own `zh_cn.json`, i.e. they match what the game displays.

---

## 1. What this mod does

Create's Sequenced Assembly is a multi-step production line: one work-in-progress item gets fed, pressed, cut and assembled over several steps before it becomes a product, and products may be probabilistic while scraps drop along the way. Refined Storage autocrafting happily queues jobs, counts materials and requests more of them — but it only understands "pattern: one input set in, one output out" and has no idea which step of the sequence an item is currently on.

This mod is the translation layer between the two:

- **Just order it in Refined Storage.** Once the product is written into a Sequence Assembly Pattern and placed into a Sequence Assembly Pattern Vault, it appears in your terminals like any other autocrafting pattern; RS handles extraction, restocking and task scheduling.
- **Chambers drive the machines.** Place a Sequence Execution Chamber against a Create machine, insert that step's Unit Pattern, and it will claim raw materials and intermediates from the network, feed the machine in front of it, and reclaim the results.
- **Products return to the network.** Finished products go back into the RS network and count towards the task; intermediates (transitional items) are written straight back to the network so the chamber responsible for the next step can claim them.
- **Probability and scrap are taken seriously.** Recipes whose main product is not guaranteed are handled properly: the Precision Mechanism result pool has weights `120 / 8 / 8 / 5 / 3 / 2 / 2`, so the UI shows 81% for the product and each scrap shows its own percentage, and feeding advances in batches according to those chances. Scrap also has its own category and can be collected separately.
- **Stalls are reported.** Missing materials, offline executors, downstream back-pressure and long periods without progress are all detected by a watchdog that suspends the task and shows a banner with "Resume" and "Change machine" buttons in the Autocrafting Monitor.

Besides sequenced assembly, the mod ships support machines: Schematic Cannon Loaders, a Collection Cache, Resource Quantity Keepers, a Range Charger, Universal Storage Disks, Separation/Camouflage Frames, and an Advanced Remote Multifunctional Terminal that can switch between several terminal screens.

---

## 2. Core concepts

| Concept | One-line explanation |
| --- | --- |
| **Sequence Assembly Pattern** (`sequence_assembly_pattern`) | The face of the whole line: starting ingredient, products, scraps and total step count. Put it into the vault and RS sees a normal autocrafting pattern. |
| **Sequence Unit Pattern** (`sequence_unit_pattern`) | Describes **one** step: name, recipe type (e.g. `create:pressing`), whether it consumes an input, and which input. This is what goes into a chamber. |
| **Sequence Assembly Pattern Vault** (`sequence_assembly_executor`) | Holds the master pattern and acts like an autocrafter-style block. Insert the master pattern and connect it to the network and the pattern is registered as autocraftable. It does not face a machine and does not drive any production line. |
| **Sequence Execution Chamber** (`sequence_execution_chamber`) | The block that does the work, placed directly against a Create machine (six-direction facing, including up and down). Stores unit patterns (54 slots), feeds the machine and reclaims output. |
| **Sequence Pattern Terminal** (`sequence_pattern_terminal`) | The editing entry point: import a Create sequenced assembly recipe (JEI's `+` button), assign a machine to each step, and generate the master pattern plus unit patterns. The flow itself is **read-only** so it cannot be broken by accident. |
| **Unit Pattern Manager** (`unit_pattern_manager`) | Lists the unit patterns of every chamber in the network, grouped per chamber (merged once chained); master patterns still live in the Autocrafter Manager. |
| **Input Bus / Output Bus** (RS's Importer / Exporter) | Once attached to a chamber they automatically become "extended": the output bus pushes materials and intermediates from the chamber to the machine in front of it, and the input bus collects things from the chamber back into the network. |
| **Categories** | The selectable resource groups on a bus, six in total: **Materials** (starting ingredient), **Feedstock** (consumed mid-step), **Fluids**, **Intermediates**, **Products**, **Scrap**. |
| **Chaining** | Several chambers of the same recipe type pointing at each other become one "logical chamber". Capacity and unit patterns merge, and the name and recipe type are shared across the whole chain (change any one, the whole chain follows). This is how you scale up. |
| **Intermediate Cache Warehouse** (`intermediate_cache`) | 27 disk slots. With RS storage disks inserted it becomes a temporary store for intermediates **shared by every chamber in the network**; several of these merge into one pool automatically. |
| **Shared machine queueing** | When one machine is shared by several recipes, they queue by order time: whoever ordered first occupies that machine until it is finished (or has nothing to work on), then the next recipe gets its turn. |
| **Intermediate reuse first** | A toggle. When enabled, starting a new item may begin from an intermediate already present in the network, skipping the steps that were already done. |
| **Separation Frames / wrench disconnection** | Two ways to isolate cabling: a Separation Frame wraps a cable or fluid pipe and freezes its connections exactly as they were at the moment it was applied, while the Create wrench can disconnect/restore a single cable seam directly (default mode `seam`). |
| **Quick dismantling** | Sneak + right-click with a wrench breaks this mod's blocks directly, matching Refined Storage's own mechanism. |
| **Clipboard bulk configuration** | Hold a Create clipboard: right-click a bus to copy its configuration, left-click to paste onto that one bus, sneak + left-click to paste onto every same-kind bus in the same cable cluster. |

---

## 3. Installation

### Required dependencies

| Mod | Version range | Notes |
| --- | --- | --- |
| NeoForge | `[21,)` | Mod loader |
| Create | `[6.0,)` | Sequenced assembly, wrench, schematic cannon, fluid pipes |
| Refined Storage | `[2.0.0,3.0.0)` | Network, terminals, buses, autocrafting |
| Curios API | `[9.0.0,)` | Curios slot and hotkey for the Advanced Remote Multifunctional Terminal |
| Refined Storage - Curios Integration | `[1.0.0,)` | Provides the Curios slot that holds this mod's remote terminals. It has no stable Maven artifact (published on CurseForge / Modrinth only), but data files cannot be conditional, so it is listed as required |

### Optional dependencies (the mod loads and works without them, only the feature is absent)

| Mod | Version range | What it adds |
| --- | --- | --- |
| JEI | `[19.0,20.0)` (client only) | Press `+` on a sequenced assembly recipe page to import the whole flow into the Sequence Pattern Terminal |
| Jade | `[15.0.0,)` (client only) | Shows the real identity of a camouflaged or sheathed block when aimed at, and shows which chamber an input/output bus is bound to plus what it is responsible for |
| FTB Ultimine | `[2101.1.0,)` | Hold a frame and the Ultimine key, then right-click to wrap a whole same-family cable/pipe run at once (limited by FTB's own `max_blocks`) |
| Create Enchantment Industry | — | Lets the Collection Cache store experience as liquid experience; without it only experience nuggets are available |

### How to install

Drop this mod's jar into `.minecraft/mods` together with the required dependencies above. In development the project uses `run/mods`.

---

## 4. Quick start

1. **Build a Create line.** Presses, deployers, spouts, depots — whatever the sequenced assembly recipe you want needs.
2. **Attach chambers.** Place a Sequence Execution Chamber next to the machine. Its facing side is by default the **material input face** (the side it actively feeds the machine from).
3. **Give the chamber an identity.** Open the chamber's "Config" screen and set a **recipe type** (e.g. `create:pressing`) and a **name**. Unit patterns can only be inserted when their recipe type matches the chamber exactly.
4. **Make patterns.** Place a Sequence Pattern Terminal and put one Refined Storage pattern (`refinedstorage:pattern`) into its pattern input slot as generation material. Import a Create sequenced assembly recipe with JEI's `+` button, or via the in-screen import.
5. **Assign a machine to every step.** In the flow arrangement, click a step and pick a chamber. Each step's machine position is stored in the master pattern, so it is unambiguous.
6. **Generate.** Click "Generate patterns": you get one **master pattern** and one **unit pattern per step** (written into the terminal's unit pattern library). The master pattern slot is output-only — to generate another one you must take the existing one out first.
7. **Distribute the unit patterns.** Put each unit pattern into the chamber responsible for that step (right-click with it in hand, or use the "Store" button in the Chamber Unit Pattern Summary screen).
8. **Register the master pattern.** Place a Sequence Assembly Pattern Vault, connect it to the RS network, and insert the master pattern. From that moment the product is an autocraftable pattern in the network.
9. **Wire the buses.** Connect RS Input/Output Buses to the chamber with RS cables (both automatically become "extended" and their UI turns into category selection). Alternatively skip the buses and use the chamber's own face configuration to set material input / product output / intermediate output per face.
10. **Add an Intermediate Cache Warehouse** (strongly recommended) with RS storage disks, so intermediates have somewhere to live.
11. **Order it.** Craft the master pattern's product from any RS terminal and watch the Autocrafting Monitor.

---

## 5. Interfaces and controls

### Sequence Pattern Terminal
- Import a Create sequenced assembly recipe: press `+` on the JEI recipe page (only the Sequenced Assembly category is wired), or use the in-screen import.
- The flow arrangement is **read-only**: machine assignment can be changed, the flow itself comes from the recipe.
- Each step has a "skip duplicate unit pattern when generating" toggle; product and scrap amounts/chances are configurable.
- Generation material is taken from the terminal's **pattern input slot** (Refined Storage patterns).
- Left-click the "Unit Pattern Library" header to open the **Chamber Unit Pattern Summary**: it lists every chamber, its recipe id and the unit patterns it holds, with "Retrieve" and "Store" actions.
- When the network has no Intermediate Cache Warehouse the terminal shows a hint that intermediates are invisible to the terminal.

### Sequence Execution Chamber
- The title row has a "Config" button (name / recipe type / chain status) and a "Face" button; the output mode switches between **face output** and **bus output**.
- **Face configuration** has four modes: None, Material Input (push to the adjacent machine), Product Output (pull products from the adjacent container into the chamber), Intermediate Output (pull transitional items from the adjacent container and write them straight back to the network). The machine-facing side defaults to Material Input.
- Like autocrafters, chambers are **extract-only**: external logistics cannot insert into them; they can only pull from the output/intermediate faces.

### Buses (extended)
- Input/Output Buses connected to a chamber through RS cables automatically become extended, and their UI becomes the "detailed category configuration": recipe tabs plus six groups (Materials, Feedstock, Fluids, Intermediates, Products, Scrap) with search, folding and a "clear all" button.
- The "Normal" button on the category bar turns a bus back into a plain bus; "Restore" re-enables automatic wiring-based detection.
- **Filter priority**: when the RS filter slots of an input/output bus **hold something**, that bus is treated as a **plain** bus (an empty filter keeps it extended). The verdict reads RS's own filter container, is computed live rather than cached, and flips the moment the filter changes; the UI path and the transport path share the same verdict, so the UI can never say "plain" while transport still works by category.
- Ownership rule: a bus that reaches **exactly one chain** owns it. Only reaching **two or more different chains** (regardless of distance) marks it as "ownership undetermined" and **disables the extended mode** with a banner. **"This pass has not finished scanning yet" no longer disables anything** — the search is resumable and a verdict is published only when the whole run has been scanned, with the previous complete result staying in force while it resumes. Buses further down a long cable run are therefore no longer silently missed, and the verdict no longer changes with which chunks happen to be loaded. The "Show reachable area" button paints the reachable chambers and cable run with a translucent overlay.
- The same category may be selected by several output buses at once; the chamber round-robins between them.

### Jade bus tooltips (optional dependency)
Aiming at an input or output bus, Jade tells you three things:

- which chamber that bus is **bound to**;
- what it is **responsible for right now**, listed per category (Materials, Feedstock, Fluids, Intermediates, Products, Scrap);
- that an input bus is in **fully automatic** mode, called out explicitly.

The display mode is controlled by `jadeBusTooltipMode`: the default is `always` (shown permanently), and it can be switched to `shift` (only shown while Shift is held, otherwise a single hint line remains). Each group lists at most three representative items and truncates the rest to "…" without writing a count. Without Jade installed that section simply does not exist and nothing else is affected.

### Bulk bus configuration with the Create clipboard
Configuring buses one by one is tedious, so a Create clipboard can configure a whole area at once. The gestures match Create's own "copy / paste block configuration" convention:

| Gesture | Effect |
| --- | --- |
| Hold the clipboard and **right-click** a bus | Copy that bus's entire player configuration (RS filter slots and fuzzy mode, this mod's category selection, "fully automatic collection", forced plain bus) |
| Hold the clipboard and **left-click** a bus | Paste the configuration onto **that one** bus |
| **Sneak** + left-click a bus | Paste onto **every same-kind bus in the same cable cluster** |

Boundaries: only the same cable cluster (seams cut by the wrench and seams frozen by a Separation Frame both count as cut), only the same kind of bus, at most 64 buses, never across dimensions; if the kind or version does not match, not a single byte is written. The payload replaces **only this mod's own keys**, so nothing another mod copied is wiped, and reading, validating and writing all happen server-side.

### Quick dismantling (sneak + wrench right-click)
Refined Storage's own blocks and cables can be broken quickly with Shift + right-click while holding a wrench; this mod now behaves the same way.

- It covers **all 14 blocks this mod registers** (11 machines plus 3 frame blocks); the criterion is the registry namespace, so blocks added later are covered automatically.
- The mechanism matches Refined Storage: the wrench test uses the tool tag, a non-sneaking right-click still reaches the original interaction (screens still open), a missing build permission reuses Refined Storage's own message, and the sound is identical.
- The removal order is "spawn the drops first, then remove the block", so the content settlement this mod performs on removal (governed by `/rs_create_compat blockcontent`) and the machine-cluster content handover are never skipped.

### Range Charger and Curios
The Range Charger charges items carried by the player, covering the **main inventory, hotbar, armour, offhand and the Curios slots**. Curios slots do not recurse into containers inside containers (backpacks and similar are not searched), and they are not scanned every tick — the interval is controlled by `rangeChargerCuriosScanInterval` (default 5 ticks). Items that are already full are skipped, and one scan never charges the same item twice.

### Autocrafting Monitor
- Stalled tasks are suspended, and the banner names the reason: missing materials, offline executor, output blocked, or no progress for a long time.
- The monitor offers "Resume", "Suspend", "Change machine" and "Shortage handling (suspend / wait)".
- **A suspended task never resumes by itself** — you must press "Resume" in the monitor.
- Button visibility depends only on the action bits sent by the server and on static geometry: as long as the task still exists and RS has not reclaimed it, the primary slot (Suspend / Resume) always has exactly one button, and selecting a task the client has no snapshot for **automatically requests one read-only snapshot**. The button row sits on the same line and height as Refined Storage's own cancel button and collapses entirely when it does not fit.
- Only tasks **genuinely driven by this mod's sequenced assembly chains** are auto-suspended; an ordinary task whose product happens to also be in this mod's pattern vault is not dragged in. Even if a player suspends an ordinary task manually, it is **never** auto-cancelled after the timeout.

### Sequence Assembly Pattern Vault
- Master patterns in the vault **always show what they craft**, matching the "product shown by default" behaviour of Refined Storage's own autocrafter, with no need to hold Shift.


### Keybinds

| Key | Action |
| --- | --- |
| `G` | Open the Advanced Remote Multifunctional Terminal (needs the terminal in main hand, inventory or a Curios slot) |
| `H` | Toggle Separation Frame display (requires goggles) |
| `K` | Hide / restore filler blocks (Camouflage Frame display, requires goggles) |

### Advanced Remote Multifunctional Terminal
Switches between five screens: Crafting Grid, Pattern Terminal, Autocrafter Manager, Autocrafting Monitor and Sequence Pattern Terminal. Available as normal, fully charged and creative variants; right-click a wireless transmitter or a network node to bind it.

---

## 6. Commands

Every command is **permission-free** — any player can run it. The root command is `/rs_create_compat`.

| Command | Arguments | Effect |
| --- | --- | --- |
| `/rs_create_compat autocrafter storage` | `on` / `off` | Enable / disable the internal storage of every RS autocrafter within **32 blocks** of the executor. Turning it off first writes items and fluids back into the network; if they do not fit, the operation is cancelled |
| `/rs_create_compat blockcontent` | `network` / `drop` / `block` | Where block contents go when a block is broken: back into the network (falls back to storing inside the block when there is no network) / dropped as items / stored inside the block item and restored when placed again. Saved per world; default `drop` |
| `/rs_create_compat supply` | `materials` / `target` | Material supply strategy for Sequence Execution Chambers: only output materials (ignore chances, never start crafting) / keep supplying until the target product is satisfied (missing materials are autocrafted). Default `target` |
| `/rs_create_compat shortagemode` | `suspend` / `wait` | Shortage handling: suspend as soon as materials are missing (does not block later tasks) / keep waiting until materials arrive (never auto-suspends). Default `suspend` |
| `/rs_create_compat refill` | `on` / `off` / `machines` | Refill quantity: request the exact gap (never more) / one at a time (one per craft for deterministic recipes, batched advance for probabilistic ones) / one per machine (as many as the chamber feeds). Default `off` |
| `/rs_create_compat reuse` | `on` / `off` | Whether a new item may start from an intermediate already present in the network. Default `off` |
| `/rs_create_compat devlogs` | `on` / `off` / none | **Master switch for developer logs**, off by default. When off, all warnings and errors, the startup fingerprint, session anchors, `diag` exports, the direct consequences of player actions and throttled low-frequency explanatory INFO lines are still printed. Takes effect immediately, no restart needed. Without an argument it reports the current state and usage |
| `/rs_create_compat assemblydebug` | `on` / `off` | An **equivalent alias** of `devlogs` (the old spelling keeps working) |
| `/rs_create_compat debug assembly` | `on` / `off` | An **equivalent alias** of `devlogs` |
| `/rs_create_compat diag` | none | Exports the current state to `run/rscc_diag/<timestamp>/snapshot.json` and overwrites `run/rscc_diag/latest/snapshot.json`. Read-only and idempotent |
| `/rs_create_compat diag run` | none | Forces the diagnostic log on first, then exports the snapshot (leaving `[rscc-diag]` section markers in the log). Observes only: it places no orders and changes no task state |

For `blockcontent` / `supply` / `shortagemode` / `refill` / `reuse` / `devlogs` / `assemblydebug`, entering only the intermediate level reports the current setting plus usage hints.

---

## 7. Blocks and items

### Sequenced assembly

| Name | Registry name | Purpose |
| --- | --- | --- |
| Sequence Pattern Terminal | `sequence_pattern_terminal` | Import recipes, arrange the flow, generate master and unit patterns; the flow is read-only |
| Sequence Assembly Pattern Vault | `sequence_assembly_executor` | Insert a master pattern and connect to a network to register it as an autocrafting pattern; master patterns in the vault always show what they craft |
| Sequence Execution Chamber | `sequence_execution_chamber` | Place against a machine; stores unit patterns (54 slots), feeds the machine and reclaims products |
| Unit Pattern Manager | `unit_pattern_manager` | Manages unit patterns grouped per chamber (merged once chained) |
| Intermediate Cache Warehouse | `intermediate_cache` | 27 disk slots forming a network-wide shared intermediate cache |
| Sequence Assembly Pattern | `sequence_assembly_pattern` | The master pattern item; Shift + right-click reverts it to a blank RS pattern |
| Sequence Unit Pattern | `sequence_unit_pattern` | The unit pattern item; Shift + right-click reverts it to a blank RS pattern |
| Master pattern, **right-click air** | — | Opens the "Master Pattern Machine Binding" screen to rebind a step to another chamber (the matching unit pattern is moved along) |

### Support machines

| Name | Registry name | Purpose |
| --- | --- | --- |
| Schematic Cannon Loader | `schematic_loader` | Place against a Create Schematicannon to feed it automatically; several loaders share the supply |
| Advanced Schematic Cannon Loader | `advanced_schematic_loader` | Built-in blueprint queue: prints in order and recycles blank blueprints |
| Collection Cache | `collection_cache` | Absorbs nearby drops, fluids and gases; the match area is filtered by ghost markers and can be inverted, matched by tags, set to "collect all", or configured to void matched resources instead of returning them. Supports per-face logistic input toggles and a three-axis collection range. With the experience toggle on and Create Enchantment Industry installed, experience orbs are converted straight into liquid experience in the cache's fluid buffer (1 point = 1 mB, nuggets 3 points each); without it they fall back to nuggets |
| Resource Quantity Keeper | `quantity_keeper` | Marks a resource and holds a target amount: autocrafts when low, can void the excess |
| Advanced Resource Quantity Keeper | `advanced_quantity_keeper` | Four keepers in one, each of the four slots setting an item / fluid / gas target |
| Range Charger | `range_charger` | Powered by the RS network or FE; charges blocks and dropped items in range, and charges items carried by the player including Curios slots |
| Separation Frame / Infinite Separation Frame | `separation_frame` / `infinite_separation_frame` | Right-click a cable, fluid pipe, RS input bus, RS output bus or RS external storage bus to freeze its connections as they are; sneak-right-click to remove. The normal version is consumed and refunded, the infinite version is neither |
| Camouflage Frame | `camouflage_frame` | Right-click to wrap a cable, pipe, RS input bus, RS output bus or RS external storage bus with a shell; right-click with the same block to rotate it; sneak-right-click to take it off |

### Items

| Name | Registry name | Purpose |
| --- | --- | --- |
| Advanced Remote Multifunctional Terminal | `advanced_remote_terminal` | Switches between five terminal screens; can be stored in a Curios slot and opened with `G` |
| Advanced Remote Multifunctional Terminal (charged) | `advanced_remote_terminal_charged` | Same, created fully charged |
| Creative Advanced Remote Multifunctional Terminal | `creative_advanced_remote_terminal` | Same, ignores energy |
| Universal Storage Disk | `universal_storage_disk_1k` … `universal_storage_disk_64m` | Nine tiers: 1K / 4K / 16K / 64K / 256K / 1M / 4M / 16M / 64M; stores items, fluids and gases together |
| Infinite Universal Storage Disk | `universal_storage_disk_creative` | Creative-only, unlimited capacity |

The mod also adds a full advancement tree, for example "Sample Mastery", "The Whole Picture", "Clean Cut" and "Disk Overflowing".

---

## 8. Configuration

The config file groups its options by purpose; only the ones that change gameplay are listed here (the rest are energy costs and capacity numbers):

| Option | Default | Meaning |
| --- | --- | --- |
| `wrenchCableDisconnectMode` | `seam` | How the wrench disconnects RS cables: `seam` = right-click the seam between two cables to disconnect/restore that one connection; `face` = toggle "this face auto-connects" per face; `off` = this mod does not interfere at all. Requires a restart |
| `frameArbitraryBlocks` | `false` | Whether frames may wrap arbitrary full blocks (machines, containers, …). When `false` only RS cable-family blocks and Create fluid pipe-family blocks are accepted. Requires a restart |
| `devLogs` | `false` | Master switch for developer/diagnostic logs (keep off for normal play). When on it prints large volumes of `[rscc-assembly]` / `[rscc-trace]` / `[loader]` / `[rscc-ledger]` lines; when off, warnings and errors, the startup build line, session anchors, `diag` exports, the direct consequences of player actions and throttled low-frequency explanatory INFO lines are still printed. Toggle in game with `/rs_create_compat devlogs on\|off` (aliases: `assemblydebug`, `debug assembly`) |
| `jadeBusTooltipMode` | `always` | How the Jade tooltip for input/output buses is shown: `always` = permanently visible; `shift` = only while Shift is held (otherwise a single "hold Shift to inspect the bus" line remains). Purely a client-side presentation setting: a dedicated server cannot read or use it, so single-player and server behaviour never diverge. It is re-read on every tooltip draw, so reloading the config takes effect immediately |
| `rangeChargerCuriosScanInterval` | `5` | Interval in ticks at which the Range Charger scans Curios slots, range `1` to `1200`. The inventory family (main inventory / hotbar / armour / offhand) is still scanned every tick; this only applies to the Curios path, and the default keeps end-to-end latency under one second |
| `autocrafterStorageEnabled` | `true` | Master switch for autocrafter internal storage. When disabled, `/rs_create_compat autocrafter storage on` is refused so products cannot get stuck inside |
| `autocrafterOutputSlots` / `autocrafterFluidCapacity` | `256` / `256000` | Slot count and fluid capacity of the internal storage used by autocrafters and chambers |
| `universalDiskAllowMixedTypes` | `true` | Whether one Universal Storage Disk may hold different resource types at the same time |
| `tagFilterEnabled` | `true` | Allows input/output buses to filter by tag using an item carrying the `tag_filter` data component |
| `assemblySuspendOverflowReclaim` | `false` | After a task stays suspended longer than `assemblySuspendLimitTicks` (12000 ticks = 10 minutes): `false` = keep it suspended; `true` = mark it failed and reclaim safely through RS's own cancel path, returning extracted intermediates to the network without destroying anything |
| `assemblyOfflinePersistTicks` / `assemblyNoProgressTimeoutTicks` | `40` / `600` | Thresholds for "offline" and "no progress" detection |
| `advancedRemoteTerminalEnable*` | all `true` | Per-screen switches for the Advanced Remote Multifunctional Terminal (grid / pattern terminal / manager / monitor / sequence terminal) |

---

## 9. Known limitations and caveats

1. **Patterns are static snapshots taken at generation time.** After changing the flow, swapping a machine, or updating the mod, you must **regenerate** the master and unit patterns for the new configuration to take effect. The master pattern slot is output-only: while it holds a pattern the generate button is greyed out, so the old one must be taken out first. Patterns from older saves do not carry candidate groups (for example the iron-nugget / zinc-nugget alternatives of Create Track) and need one regeneration.
2. **Face output mode is not yet fully guarded.** The upstream technical notes state that the `FACE` path lacks the set of safety gates the `BUS` path has, so the starting ingredient can be inserted again once a machine has already turned it into a transitional item, producing an extra in-flight item (visible as surplus intermediates). Real-machine verification was done on bus output mode; evaluate this before switching to face output.
3. **A bus with undetermined ownership disables its whole extended mode.** As soon as an input/output bus can reach **two or more different chains** (regardless of distance), it is marked "ownership undetermined", the extended mode is disabled and a banner appears. Fix it by isolating cables with Separation Frames or the wrench. **"This pass has not finished scanning yet" no longer triggers it** — the search is resumable and a verdict is published only when the whole run has been scanned, with the previous complete result staying in force while it resumes; buses further down a long cable run are no longer silently missed, and loading new chunks no longer flips the verdict. If the cable network is so large that a single pass hits the hard cap, the mod reports it as "not exhaustive" honestly and leaves a checkable log line rather than silently discarding the result.
4. **Shared machine queueing reads "order time".** It takes RS's task `startTime` (wall-clock milliseconds): two orders within the same millisecond are tie-broken by recipe id in lexicographic order — deterministic, but not necessarily the true order. Changing the server clock, or importing tasks from another save, can make the order differ from intuition; a recipe whose order time cannot be read **does not take part in machine ownership** (it sorts last).
5. **Intermediates are only visible once a cache exists.** Without an Intermediate Cache Warehouse in the network, intermediates only live in the network and in chamber internal storage, and the pattern terminal hints that intermediates are invisible to the terminal and suggests adding one.
6. **Suspension never heals itself.** Offline executors, missing materials, output blockage and lack of progress all suspend the task, and the only way back is pressing "Resume" in the Autocrafting Monitor. Resuming merely clears the suspension and restarts the detector's timer; if materials are still missing it suspends again.
7. **The bus category screen still lacks a text input.** It is recorded as an unfinished item upstream (the user has not yet specified what the input field is for).
8. **Gases are not covered by the "blocked" verdict.** The Collection Cache's blocking feature only covers items and fluids; gases have no separate resource type in RS 2.0, and that entry point is currently reserved.
9. **The terminal's candidate cache is write-only.** If the first frame computes an empty table while recipes or slots are not ready, that empty result is cached for the rest of the session. A "candidates persisted on the pattern" fallback softens the impact, but it is still recorded as a robustness concern.
10. **Extract-only chambers are intentional.** Their external logistics capability is wrapped in an extract-only view, so hoppers and pipes cannot push into them; feed the line through output buses or the chamber's material input face instead.
11. **"Not exhaustive" does not mean "fine".** When the cable search hits its single-pass hard cap (4096 cells) the mod reports it as "not exhaustive", publishes that result honestly and leaves a checkable log line rather than silently discarding it. Cable networks of that size are hard to maintain anyway; consider splitting the topology with Separation Frames.
12. **Structure cache invalidation is deliberately coarse.** A block change unrelated to structure (a stone placed far away, for instance) also makes the next read rebuild once. Rebuilding is bounded by a small derivation of at most 8 chambers, which is the intended "rather invalidate too often than miss an invalidation" trade-off. The cache lives in memory only and is never written to the save, and it never caches dynamic quantities such as stock, machine busy state, refusals or in-flight counts.
13. **Clipboard cluster paste is bounded.** Only the same cable cluster, only the same kind of bus, at most 64 buses, never across dimensions; anything outside that is skipped rather than partially written.
14. **Jade tooltips truncate.** Each group lists at most three representative items and truncates the rest to "…" without a count — deliberately, so the tooltip fits. Open the bus screen to see everything.
15. **Curios charging is throttled.** The default 5-tick scan means "put it in and it is instantly full" does not happen; the delay stays under one second, and lowering `rangeChargerCuriosScanInterval` (minimum 1) makes it faster.
16. **Most fixes in 1.1.0 rest on source cross-verification and model derivation, which is not the same as in-game verification.** The self-check scripts state "no in-game verification" for bus allocation, the reservation model, the structure cache and the cable search. Every assertion can be falsified by a source fact or a model counter-example, and the models are structurally identical to the implementation, but that is still not the same as having run in a real world — verify with a small batch of orders before upgrading a large production line.

---

## 10. FAQ

**Q1: I placed the order but the machines do nothing and no materials are consumed.**
Check in this order:
1. Is the master pattern actually inside the Sequence Assembly Pattern Vault's slot? Without it the network has no such autocrafting pattern at all.
2. Look for the banner "**Bus not configured: paused, no materials will be consumed**" — it names the step whose input/output bus configuration is missing.
3. Is every step's chamber online, and does its recipe type match? You can reassign a step from the terminal, and the monitor also offers "Change machine".
4. Run `/rs_create_compat diag` and then look at the `push_stalled` lines in `run/logs/latest.log`: `reason=STEP_OWNER_MISSING` means no online chamber claims that step, `reason=DESTINATION_REFUSED` means the downstream machine refuses the item.
5. Are the unit patterns inside the chambers? Use the `chambers` section of the diag snapshot to confirm each chamber's unit patterns and its recipe type.

**Q2: The bus screen turned into a plain input/output bus screen and the category list is gone.**
That means the extended bus has **undetermined ownership** and was disabled (a banner is shown). The cause is that its cable cluster reaches **two or more different chains**.
- Use "Show reachable area" to see which chambers it reaches.
- Wrap the cable/pipe section you want to isolate with a **Separation Frame**, or **disconnect the seam with the wrench** (default mode `seam`; the config can switch to per-face `face`).
- Alternatively move those chambers apart.
- If you wanted a plain bus anyway, press "Normal" on the category bar; "Restore" brings automatic detection back.
- If it became a plain bus because its **filter slots hold something**, that is the "filter priority" rule doing its job: clear the filter slots and it becomes extended again.

> Since `1.1.0`, "this pass has not finished scanning yet" **no longer** disables anything. Buses that used to be mis-disabled or silently turned plain because the cable run or the bus count was too large recover by themselves after upgrading — no need to rewire anything.

**Q3: The monitor shows "Output blocked: step N …, the task cannot continue; press Resume in the monitor".**
This means the downstream station refuses input, normally because something is sitting on it:
1. Check whether that machine (or depot) is holding **scrap** or another in-flight item.
2. Clear whatever is blocking it (scrap can be collected through the output bus's "Scrap" category or by a Collection Cache).
3. Go back to the Autocrafting Monitor and press "Resume" — it will not recover on its own.
4. To reduce this kind of blockage, use `/rs_create_compat refill machines` so the chamber sends one portion per machine it feeds instead of flooding them.

**Q4: Intermediates keep piling up and the terminal cannot see them.**
1. Place an **Intermediate Cache Warehouse**, connect it to the network and insert RS storage disks (27 disk slots; several warehouses merge into a single pool). The invisible intermediate entries appear in the terminal immediately.
2. Open the chamber screen to see its internal storage and determine whether the intermediates sit in the chamber or on the machines.
3. Run `/rs_create_compat reuse on` so new items start from intermediates already in the network instead of replaying every step from scratch.
4. Run `/rs_create_compat supply target` (the default) so missing materials are autocrafted.

**Q5: I replaced a machine for one step (or broke and replaced it) and now that step no longer works.**
Patterns store machine positions, so after swapping a machine you must reassign it:
1. Hold the **master pattern and right-click air** to open "Master Pattern Machine Binding", pick the step and press "Change machine" (the step's unit pattern is moved along with it).
2. Or reassign the step from the terminal.
3. Afterwards, consider regenerating the master and unit patterns so what runs on the line matches what you see (remember the master pattern slot is output-only — take the old one out first).

**Q6: A unit pattern refuses to go into a chamber.**
Three conditions must hold at once:
1. It must be a **Sequence Unit Pattern** (plain RS patterns are always rejected).
2. The chamber must already have both a **recipe type** and a **name**.
3. The pattern's recipe type must be **exactly equal** to the chamber's bound recipe type.
On a chain, the name and recipe type are held only by the chain head, so changing any member changes the whole chain.

**Q7: I want to break a machine but it still holds items; I am afraid of losing them.**
Decide where contents should go first: `/rs_create_compat blockcontent network` (back into the network, or stored inside the block when there is no network), `drop` (drop as items, the default), or `block` (stored in the block item and restored when placed again). Chambers, the pattern vault, the Intermediate Cache Warehouse and the other machines all follow this setting.

---

## 11. Collecting evidence when something goes wrong

- **One-shot snapshot**: `/rs_create_compat diag`. It writes `run/rscc_diag/<timestamp>/snapshot.json` and also overwrites `run/rscc_diag/latest/snapshot.json`. It contains each chamber's contents and claimed steps, each bus's selected categories and linked executors, the full NBT of every block entity, the per-slot contents of every container, the resource table of every Universal Storage Disk, the player's held item, shortage and suspension records, and the conservation ledger. It is read-only and idempotent, so it can be exported while the line is running.
- **Logs**: `run/logs/latest.log` holds this mod's structured diagnostic lines; `run/logs/debug.log` holds Refined Storage's own task lines (`Created task …`, `Task … state changed …`).
- **Log prefixes**:

| Prefix | Content |
| --- | --- |
| `[rscc-build]` | Build fingerprint (git revision / branch / build time / source count / MC / NeoForge / Java), exactly one line per launch |
| `[rscc]` | Diagnostic anchors (session start, shortage and refill settings); `[rscc-diag]` marks snapshot sections |
| `[rscc-assembly]` / `[rscc-trace]` | Sequenced assembly event stream and end-to-end tracing |
| `[rscc-ledger]` | Conservation ledger: silent while balanced, warns when it is not |
| `[rscc-dedupe]` | Unit pattern deduplication decisions: why a step was skipped and which pattern it duplicated |
| `push_stalled` | Why an item could not be pushed (machine full, or no chamber claims that step) |

Diagnostic logging is **off by default** (`devLogs`, same name as the config option). Turn it on with `/rs_create_compat devlogs on` (the old `/rs_create_compat assemblydebug on` works too) and no restart is needed. While it is off, warnings and errors, the `[rscc-build]` startup fingerprint, session anchors, `diag` exports and the direct consequences of player actions are still printed.

---

## 12. Dependencies and credits

- [NeoForge](https://neoforged.net/) — mod loader
- [Create](https://modrinth.com/mod/create) — sequenced assembly, wrench, schematic cannon, fluid pipes
- [Refined Storage](https://modrinth.com/mod/refined-storage) — network, terminals, buses, autocrafting
- [Curios API](https://modrinth.com/mod/curios) — Curios slot and terminal hotkey
- [Refined Storage - Curios Integration](https://github.com/refinedmods/refinedstorage-curios-integration) — provides the Curios slot that holds the remote terminals
- [JEI](https://www.curseforge.com/minecraft/mc-mods/jei) — one-click sequenced assembly recipe import
- [Jade](https://modrinth.com/mod/jade) — aimed-at hints for camouflaged and sheathed blocks
- [FTB Ultimine (NeoForge)](https://www.curseforge.com/minecraft/mc-mods/ftb-ultimine-forge) — chained framing

---

## 13. License

`All Rights Reserved`. This is the value of `mod_license` in `gradle.properties` and of the `license` field in `neoforge.mods.toml`: the author grants no additional permissions, so redistribution, modification or commercial use of the source or artifacts requires the author's consent first.

---

## 14. Documentation in this repository

For implementation details or deeper troubleshooting, the repository ships design documents:

| Document | Content |
| --- | --- |
| `VERSIONING.md` | Versioning policy: the criteria for each of `major.minor.patch`, pre-release suffixes, the three GitHub Release states, the release process and discipline, where the version number lives, and compatibility promises |
| `RELEASE_1.1.0.md` | Release notes for `1.1.0` (severe fixes, new capabilities, performance, config options and commands, upgrade notes, known limitations) |
| `RELEASE_1.0.0.md` | Release notes for `1.0.0` (first stable release) |
| `TECHNICAL_HANDOFF.md` | Project overview, architecture, key files, known issues and fix history |
| `SEQUENCE_ASSEMBLY_HANDOFF.md` | Full handover of the sequenced assembly subsystem (category model, one-way data flow, watchdog, conservation ledger) |
| `docs/SEQUENCE_CHAIN_REDESIGN.md` | Structural facts about three Create sequenced assembly recipes and the chain redesign |
| `docs/GEAR_BLOCKAGE_AND_FLOW_CHECK.md` | Executable self-check criteria and regression signals for cogwheel blockage / feed stalls |
| `docs/SEQUENCE_ASSEMBLY_LOG_GUIDE.md` | Log forensics guide |
| `docs/BLOCKAGE_EXPERIMENT_PROTOCOL.md` | Six-segment blockage experiment protocol |
| `docs/DESIGN_DECISIONS_ROUND4.md` | Face configuration, bus output, category model, shared round-robin and other design decisions across rounds |
| `docs/dependency-policy.md` | Engineering policy for optional dependencies |
