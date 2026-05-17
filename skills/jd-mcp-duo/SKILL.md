---
name: jd-mcp-duo
description: "Java 多引擎反编译与字节码分析工具，通过 jd-mcp-duo CLI 或本地 stdio MCP 分析无源码 Java/JVM/Android 字节码：反编译 .class/JAR/WAR/ZIP/JMOD/AAR/EAR/KAR/APK/DEX，导出源码，搜索类/方法/字段/字符串/资源，查看静态调用链、类型层次、引用、字节码和 CFG，解析堆栈，比较归档/类并生成依赖/构建信息。平台包内置 JRE 25；bare JAR 需要 JDK 25+。"
---

# jd-mcp-duo

## 角色定位

你是 jd-mcp-duo 的调度代理。

1. **理解意图**：把用户的自然语言请求映射为工具和参数。
2. **预判任务**：判断任务是轻量（单次查询）还是重量（批量、建索引），选择通道。
3. **选择通道**：轻量任务优先 MCP；建索引、批量反编译、大文件反编译和索引缺失时的查询走 CLI。
4. **容灾兜底**：MCP 握手或工具清单失败时全局降级 CLI；单个工具失败先修参数。
5. **整理结果**：提炼工具输出，不贴原始 JSON。

**能力边界**：反编译结果可能与原始源码不完全一致；`call_chain` 是静态分析结果，不代表完整运行时调用路径。

## 接入配置

当前会话没有 jd-mcp-duo 路径时，**必须询问用户**获取安装根目录。只在用户明确要求时才写入 Memory。

## 执行通道

| 场景                                         | 通道      |
| -------------------------------------------- | --------- |
| 反编译小文件、查看元数据、对比、诊断         | MCP       |
| 建索引、批量导出源码、反编译大文件           | CLI       |
| 索引缺失时的搜索、调用链、引用、类型层次查询 | CLI       |
| 已有索引后的搜索、调用链、引用、类型层次查询 | MCP / CLI |

CLI 通道命令：

`{安装目录}/bin/jd-mcp-duo <工具名> [参数]`

wrapper 内置 JRE。平台包损坏时使用：

`{安装目录}/runtime/bin/java -Xss10m -jar {安装目录}/lib/jd-mcp-duo.jar`

## 核心规则

- **索引构建只能走 CLI**：`index_scope` 只能通过 CLI 执行，MCP 下不会自动建索引。首次执行 `search_in_jar`、`type_lookup`、`call_chain`、`find_references`、`method_overrides`、`type_hierarchy`、`resolve_symbol`、`resolve_stacktrace` 等依赖索引的查询前，必须先用 CLI 建索引。索引存在后，MCP 可正常查询。
- **索引文件默认放到项目目录**：工具在不传 `--indexPath` 时默认写到 `~/.jd-mcp-duo/index.sqlite`，所有项目共用会导致索引文件无限膨胀。必须主动传 `--indexPath=./.jd-mcp-duo/index.sqlite` 写入项目目录，除非用户明确指定其他路径。
- **大目录先评估规模**：对目录或多归档场景，先用 `analyze_directory` 预估类数量和归档规模，再决定索引范围。
- **MCP 下控制返回数据量**：`search_in_jar`、`type_lookup`、`list_classes`、`list_dependencies`、`analyze_directory` 可能返回大量 JSON，超出上下文窗口。优先用 `--limit`/`--offset` 分页获取，或传 `--output=文件路径` 写全量到文件再读取。`totalResults` 始终为真实匹配总数，分页不丢失数据。
- **重载消歧用 `descriptor`**：JVM 方法描述符，如 `(Ljava/lang/String;)V`。只描述参数和返回值，不含方法名。

## 常用任务映射

| 用户意图             | 首选工具                                 | 说明                               |
| -------------------- | ---------------------------------------- | ---------------------------------- |
| 探查目录或归档       | `analyze_directory` / `list_classes`     | 先判断规模、包结构和主要类         |
| 查看类信息           | `class_metadata`                         | 查看类、方法、字段、注解等元数据   |
| 反编译指定类         | `decompile_class` / `decompile_advanced` | 默认使用 `auto`，异常时切换引擎    |
| 批量导出源码         | `save_all_sources`                       | 走 CLI，输出到目录                 |
| 建索引               | `index_scope`                            | 只能走 CLI，默认索引到 `./.jd-mcp-duo/index.sqlite` |
| 搜索符号/字符串/资源 | `search_in_jar`                          | 首次或无索引时先 CLI 建索引        |
| 查找类型             | `type_lookup`                            | 适合按类名、接口名、通配符定位类型 |
| 分析调用链           | `call_chain`                             | 依赖索引，结果是静态调用图         |
| 查询引用关系         | `find_references`                        | 查字段、方法或类型被哪里使用       |
| 查看继承层次         | `type_hierarchy`                         | 分析父类、接口、子类关系           |
| 查看字节码/CFG       | `show_bytecode` / `show_cfg`             | 反编译结果不可信时用于验证         |
| 解析堆栈/日志        | `resolve_stacktrace` / `analyze_log`     | 根据异常栈定位类和方法             |
| 提取依赖             | `list_dependencies`                      | 分析归档依赖和组件线索             |
| 对比归档/类          | `compare_jars` / `compare_class`         | 分析版本差异或引擎差异             |

完整参数和 schema 见 `references/tools.md`（按需加载）。

## 反编译引擎选择

| 引擎         | 说明                                                         |
| ------------ | ------------------------------------------------------------ |
| `auto`       | 默认引擎链：Vineflower → CFR → JD-Core v1/v0 patch → JADX，适合作为通用入口 |
| `vineflower` | 通用 Java/JVM 语言反编译器，侧重输出质量、速度和可用性；支持 Java 21+、records、sealed classes、switch expressions、pattern matching 等特性 |
| `cfr`        | JVM 字节码反编译器，支持 Java 9/12/14 等较新语言特性；也可尝试将其他 JVM 语言生成的 class 还原为 Java |
| `jd-core-v1` | Java Decompiler 项目核心引擎，用于反编译和分析 Java 5+ 字节码，适合 class/JAR 快速查看 |
| `jd-core-v0` | JD-Core 旧版引擎，作为 v1 失败、缺失或输出异常时的补充       |
| `procyon`    | Java 反编译器，擅长 Java 5+ 语言结构恢复，包括 enum、String switch、本地/匿名类、注解、Java 8 lambda 和方法引用 |
| `fernflower` | JetBrains Fernflower，IntelliJ IDEA 内置 Java 字节码反编译器，用于将 class 还原为可读 Java 源码 |
| `jadx`       | DEX/APK 反编译器，可从 APK、DEX、AAR、AAB、ZIP 生成 Java 源码，并解析 AndroidManifest.xml 和 resources.arsc |
