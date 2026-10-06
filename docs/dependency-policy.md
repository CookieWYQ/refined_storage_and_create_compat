# 依赖规矩：可选依赖 / 外部模组制品一律走工程内 `libs/`（本地文件依赖）

> 适用：**可选依赖**（Jade、TOP、FTB Ultimine 一类「装了更好、不装也能跑」的模组）
> 以及任何**只发布在 CurseForge / Modrinth、没有稳定 Maven 坐标**的外部制品。
>
> 起因（为什么定这条规矩）：2026-09-30 本机 `:classes` 失败，
> `Could not GET '.../Jade-1.21.1-NeoForge-15.10.5.jar' → (certificate_unknown) PKIX path building failed`
> —— 即「本机 JDK 的信任库认不出对方证书链」这类**环境问题**。
> 构建脚本不应该把「能不能编译」押在这种外部/环境因素上：制品放进工程，构建就完全离线可过。

## 1. 首选做法（硬规矩）

- 可选依赖**优先**用工程内本地文件依赖：

  ```groovy
  compileOnly(files("libs/xxx-<version>.jar"))   // 编译期需要它的 API
  runtimeOnly(files("libs/xxx-<version>.jar"))   // 开发环境（runClient / runServer）也让它真的在场
  ```

- **不要**写成联网的 Maven 坐标（`implementation("maven.modrinth:...")` 之类），
  也不要为了它新增一个只服务它的 Maven 仓库。
  例外：NeoForge / Create / Refined Storage / JEI / Curios / FTB Ultimine 这些**既有的**、
  已在用的仓库与坐标**保持原样**，本规矩只约束「新引入的可选依赖」。

## 2. 新增一个这样的依赖时，按这 5 步做

1. **下载一次**制品（浏览器 / Modrinth API / CurseForge 都行），不要把它放进构建脚本去解析。
2. **核实是合法 jar**（不要伪造/占位）：

   ```powershell
   java -jar ...  # 不需要；用 JDK 自带的 jar 工具就能列目录
   & "$env:JAVA_HOME\bin\jar.exe" tf <文件>.jar | Select-Object -First 5   # 能列出条目即合法 zip
   ```

   再确认它确实是目标模组（例如 `META-INF/neoforge.mods.toml` / `fabric.mod.json` 里的 modId + 版本）。
3. **放进** **`libs/`**，文件名统一写成 `<modid>-<version>.jar`（如 `libs/jade-15.10.5+neoforge.jar`）。
4. **在** **`build.gradle`** **登记**：`compileOnly/runtimeOnly(files("libs/..."))`，
   并在同一段注释里写清四件事（先例：Jade 那一段）：

   - **用途**（哪个类用它做什么）；

   - **来源与版本**（哪个平台 / 项目 id / 版本号）；

   - **sha1 与文件大小**（便于以后核对是不是同一份制品，`Get-FileHash <文件> -Algorithm SHA1`）；

   - **升级方式**（换文件 + 同步改注释）。
5. **`gradle.properties`** **同步说明**：不再声明「版本号属性」（避免留下“声明了却没人用 / 又在解析”的死配置），
   改为注释记录来源与 sha1。

## 3. `.gitignore` 必须保留例外

仓库有全局 `*.jar` 规则，`libs/` 下的制品必须被豁免，否则它会被**静默忽略**，
别人 clone 之后 `:classes` 会因缺类直接失败：

```gitignore
!libs/*.jar
```

## 4. 运行时仍必须**软兼容**

放进 `libs/`（`compileOnly`）**不等于**要求玩家装它。规矩照旧：

- 接入点只由对方模组自己发现（例如 Jade 的 `@WailaPlugin` 注解扫描），
  **别的地方一行都不引用**该模组的类 —— 这样未安装时 JVM 根本不会去解析它；

- 接入类**不得有静态副作用**（没有静态初始化块、没有注册动作）；

- `neoforge.mods.toml` 里声明为 `type = "optional"`；

- 缺失表现 = 「功能不出现」，而不是「启动失败」。

## 5. 现有先例

| 制品   | 文件                               | 用途                                                                | 来源 / 版本                                           | sha1                                                  |
| ---- | -------------------------------- | ----------------------------------------------------------------- | ------------------------------------------------- | ----------------------------------------------------- |
| Jade | `libs/jade-15.10.5+neoforge.jar` | 指向信息模组接入（`client/jade/RsccJadePlugin`），显示「图标=填充方块、文本=原部件类型+（被伪装）」 | Modrinth 项目 `jade`（id `nvQzSEkH`）15.10.5+neoforge | `d5bf134b3dbde9f5258666823900e21341dc0a50`（725742 字节） |

