---
name: jd-mcp-duo
description: "用于通过 jd-mcp-duo CLI 或本地 stdio MCP 分析无源码 Java/JVM/Android 字节码：反编译 .class/JAR/WAR/ZIP/JMOD/AAR/EAR/KAR/APK/DEX，导出源码，搜索类/方法/字段/字符串/资源，查看静态调用链、类型层次、引用、字节码和 CFG，解析堆栈，比较归档/类并生成依赖/构建信息。平台包内置 JRE 25；bare JAR 需要 JDK 25+。"
---

# jd-mcp-duo：Java 多引擎反编译与字节码分析 Skill

## Skill 定位

你是 jd-mcp-duo CLI 和本地 stdio MCP 服务的调度代理。你的职责：

1. **理解意图**：把用户的自然语言请求映射为工具和参数。
2. **选择通道**：交互式查询优先 MCP，批量写文件和长任务优先 CLI。
3. **容灾兜底**：MCP 握手或工具清单失败才全局降级 CLI；单个工具失败先修参数。
4. **整理结果**：提炼工具输出，不把原始 JSON 直接贴给用户。

你能做：
- 反编译 `.class`、目录、JAR、WAR、ZIP、JMOD、AAR、EAR、KAR、APK、DEX。
- 导出整个归档或目录下的所有可分析输入，并保持相对结构。
- 搜索类、构造器、方法、字段、字符串常量、模块和资源文件。
- 分析静态调用链、类型层次、方法覆写、符号引用、字节码和 CFG。
- 解析 Java stacktrace/log，查找源码，提取依赖，生成 Maven/Gradle 骨架。

你不能保证：
- 反编译源码 100% 可重新编译。
- 得到运行时行为或动态调用路径；`call_chain` 是静态字节码调用图。
- 在没有平台包自带 JRE 且系统没有 JDK 25+ 时执行 bare JAR。

## 接入配置

如果当前会话没有 jd-mcp-duo 路径，先让用户提供安装根目录或直接提供可执行命令。只在用户明确要求持久记忆时才写入 Memory。

## 调用通道优先级

调用方式按优先级降级，上一级不可用时自动尝试下一级：

1. **MCP** — 用户已配置好的 MCP 服务。交互式查询和非批量工具优先使用，零启动开销，响应最快。
2. **CLI wrapper** — `{安装目录}/bin/jd-mcp-duo(.bat)`。批量工具和 MCP 不可用时使用，wrapper 内置 JRE 路径，无需额外 Java 环境。
3. **平台 JRE + JAR** — `{安装目录}/runtime/bin/java -Xss10m -jar {安装目录}/lib/jd-mcp-duo.jar`。wrapper 损坏或不可执行时，直接用平台包自带的 JRE 启动 JAR。
4. **系统 Java + JAR** — `java -Xss10m -jar {安装目录}/lib/jd-mcp-duo.jar`。前三者全部不可用时最后兜底，需要系统已安装 JDK 25+。

> `-Xss10m` 增大线程栈，防止反编译大方法或深层调用链分析时 `StackOverflowError`。

版本通过 `--version` 读取；当前代码版本为 `4.2.5.2`。

## MCP / CLI 调用规范

| 规则 | 内容 |
|------|------|
| **MCP 启动** | 无参数启动就是本地 stdio MCP 服务。客户端必须先 `initialize`，再发 `notifications/initialized`，之后才能 `tools/list` / `tools/call`。 |
| **MCP 方法** | 支持 `initialize`、`notifications/initialized`、`tools/list`、`tools/call`、`ping`、`shutdown`、`exit`/`notifications/exit`。`help` 是工具，不是 JSON-RPC method。 |
| **MCP 返回** | 成功或工具级失败都走 `tools/call` result：`content` 文本、可选 `structuredContent`、`isError`。协议错误才走 JSON-RPC error。 |
| **MCP 进度** | 如果请求带 `_meta.progressToken`，服务会发 `notifications/progress`；stderr 仍可能有 `[jd-mcp-duo]` 摘要行。 |
| **CLI 命令** | `{CLI命令} <tool> --key=value [--json]`；也支持 `--key value`，重复 key 变数组，无值 key 变布尔 `true`。 |
| **CLI 输出** | 不带 `--json` 时 stdout 是文本摘要；带 `--json` 时 stdout 是 `{text, structuredData, isError}`。stderr 是日志、进度和完成摘要。 |
| **退出码** | CLI 工具 `isError=true` 或异常时返回 1；成功返回 0。 |
| **批量策略** | `save_all_sources`、`decompile_directory`、`batch_decompile`、`batch_decompile_jars`、`source_quality_report` 推荐 CLI，避免 MCP 上下文过大；这些工具本身仍注册为 MCP 工具。 |
| **单次查询** | `decompile_class`、搜索、元数据、调用链、字节码、CFG、依赖、比较等交互式任务优先 MCP。 |
| **描述符** | JVM descriptor 只描述参数和返回值：`--descriptor='()V'`、`--descriptor='(Ljava/lang/String;)I'`。构造器用 `--methodName='<init>'`，静态初始化器用 `--methodName='<clinit>'`。 |

性能要点：同一个 MCP 服务或同一个 CLI 批量命令会共享 JVM、索引和缓存；反复单独启动 CLI `decompile_class` 是冷启动，适合少量类，不适合成千上万类。

## 输入对象与制品布局

支持的制品形态包括 JAR、WAR、EAR、APK、DEX、class 目录和依赖目录。

- 输入：目录、单 `.class`、`.jar`、`.war`、`.zip`、`.jmod`、`.aar`、`.ear`、`.kar`、`.apk`、`.dex`。
- `.class` 单文件会用 ASM 读取真实内部类名；如果路径和包名匹配，会推导 classpath root，否则使用父目录。
- 目录会递归扫描 `.class`；非 class 文件可作为资源复制或索引。
- 归档内类路径会归一化：`BOOT-INF/classes/`、`WEB-INF/classes/`、`classes/`、`jmod/classes/`。
- 多版本 JAR 支持 `META-INF/versions/<n>/`，由 `releaseVersion` 选择不高于目标版本的最高类版本。
- 嵌套依赖会从 `BOOT-INF/lib/`、`WEB-INF/lib/`、`APP-INF/lib/`、`lib/`、`libs/`、`dependencies/` 和根级归档读取。
- EAR/KAR 中嵌套模块会作为 primary classes 暴露，可用于 `list_classes` 和 `decompile_class`。
- AAR 使用 `classes.jar` 作为主类源；APK/DEX 会先转换为 class map，Android 输入优先走 native JADX。

## 典型分析场景

### 目标制品结构探查

适用于首次接触未知 JAR / WAR / ZIP / class 目录时，快速了解目标结构、包命名空间、类清单和关键元信息。

```text
analyze_directory(path=libs_dir)                 -> 统计目录下可分析归档包的规模与分布
list_classes(path=target.jar)                    -> 枚举类清单并梳理包结构
list_classes(path=target.jar, package="com.x")   -> 聚焦指定包命名空间
class_metadata(path=target.jar, className=..)    -> 查看类、方法、字段、注解等元信息
```

### 反编译源码审阅

```text
# 已有 .java 文件时优先直接读取，不反编译
decompile_class(path=jar, className=目标类)       -> 单类反编译，返回源码和结构化元数据
decompile_advanced(path=jar, className=目标类, advancedLookup=true)
                                                  -> 依赖复杂时使用增强查找
decompile_jar(path=jar, className=目标类, decompile=true)
                                                  -> 快速分析归档并预览一个类
```

批量导出用 `save_all_sources`；需要递归处理目录下所有支持文件时用 `decompile_directory`。

### 调用链与引用追踪

```text
call_chain(path=jar, className=类, methodName=方法, direction=callees)
call_chain(path=jar, className=类, methodName=方法, direction=callers)
find_references(path=jar, kind=method, className=类, methodName=方法)
method_overrides(path=jar, className=类, methodName=方法)
type_hierarchy(path=jar, className=类)
```

跨归档分析时传 `scopePath={依赖目录}`、`scopeRecursive=true`、`indexPath=./.jd-mcp-duo/index.sqlite`。如果同名重载很多，必须补 `descriptor`。

### 类型、符号与资源检索

```text
search_in_jar(path=jar, query="关键词", type=string)
search_in_jar(path=jar, query="*Controller", type=type, queryMode=wildcard)
search_in_jar(path=jar, query="*.xml", type=resource, queryMode=wildcard)
type_lookup(path=jar, query="*Service", queryMode=wildcard)
```

`search_in_jar.type` 支持 `type`、`class`、`constructor`、`method`、`field`、`string`、`module`、`resource`、`xml`、`properties`、`service`、`manifest`、`yaml`、`json`、`all`。

### 依赖、版本与源码关联

```text
list_dependencies(path=jar, format=text)         -> 读取 META-INF/maven/**/pom.properties
build_skeleton(path=libs_dir, outputDir=out)     -> 生成 Maven/Gradle 骨架
source_lookup(path=jar, className=..)            -> 查找本地或 Maven Central 源码
compare_jars(jar1=v1.jar, jar2=v2.jar)           -> 比较归档条目 size/CRC
compare_class(leftPath=jar, className=类, leftEngine=cfr, rightEngine=vineflower)
compare_jd_core(path=jar, className=类)          -> 直接比较 JD-Core v0/v1
```

### 日志定位与字节码验证

```text
resolve_stacktrace(path=jar, text="at com.x.Service.method(Service.java:42)")
analyze_log(path=jar, textPath=log_file)
show_bytecode(path=jar, className=类)
show_cfg(path=jar, className=类, methodName=方法)
compiler_diagnostics(path=java_or_jar, className=类)
remove_unnecessary_casts(path=java_or_jar, className=类, saveTo=out.java)
```

## 分析任务与工具映射

| 用户意图 | 工具 | 关键参数 |
|----------|------|----------|
| 反编译整个归档 | `save_all_sources` | `path`, `output`；输出目录或 `.jar` 决定格式 |
| 反编译目录下所有支持文件 | `decompile_directory` | `path`, `outputDir`, `recursive` |
| 反编译指定类 | `decompile_class` / `decompile_advanced` | `path`, `className`, `engine`, `profile` |
| 搜索类/方法/字段/字符串/资源 | `search_in_jar` | `path`, `query`, `type`, `queryMode` |
| 查找类型 | `type_lookup` | `path`, `query`, `queryMode` |
| 列类清单 | `list_classes` | `path`, `package`, `includeInner`, `limit` |
| 类元数据 | `class_metadata` | `path`, `className` |
| 调用链 | `call_chain` | `path`, `className`, `methodName`, `descriptor`, `direction`, `depth` |
| 引用查询 | `find_references` | `kind`, `className`, `fieldName`/`methodName`, `descriptor` |
| 覆写关系 | `method_overrides` | `className`, `methodName`, `descriptor` |
| 类型层次 | `type_hierarchy` | `className`, `depth`, `maxNodes` |
| 堆栈解析 | `resolve_stacktrace` / `analyze_log` | `text` 或 `textPath` |
| 字节码 / CFG | `show_bytecode` / `show_cfg` | `className`, `methodName`, `descriptor` |
| 依赖和构建 | `list_dependencies` / `build_skeleton` | `path`, `outputDir` |
| 引擎信息 | `list_engines` / `describe_engine_options` | `engine` |
| 工具清单 | `help` | 无 |

完整 30+ 个工具、全部参数、必填标记和关键默认值见 `references/tools.md`。只有需要精确参数表、排查 schema、或用户要求列全量工具时加载该文件。

## 反编译引擎与 Profile

| 引擎 | 行为 |
|------|------|
| `auto` | 默认。APK/DEX 先尝试 native JADX；普通 class 先 JD-Core v1。如果 v1 输出有失败标记，则尝试 JD-Core v0 并做方法级 patch；仍不可用时按 profile fallback。 |
| `jd-core-duo` / `jd-duo` / `duo` | 只走 JD-Core v1/v0 和方法级 patch，不切到 CFR/Vineflower/JADX，适合复现 jd-gui-duo 风格。 |
| `jd-core-v1` / `jd-core` / `jd` | 只要求 JD-Core v1 成功；失败时可尝试 v0 patch，但不会切外部引擎。 |
| `jd-core-v0` / `v0` | 只用 JD-Core v0。 |
| `vineflower` / `vf` | 现代 Java 准确性优先，安全审计和复杂语法常用。 |
| `cfr` | 兼容性和稳定性好，适合作为第二意见。 |
| `procyon` | 输出可读性较好，适合对比。 |
| `fernflower` / `ff` | JetBrains Fernflower，`accurate`/`debuggable` fallback 中会使用。 |
| `jadx` | Android APK/DEX 首选；普通 class/JAR 也可显式尝试，但不一定最佳。 |

Profile fallback 顺序：

| Profile | fallback 顺序 |
|---------|---------------|
| `fast` | JD-Core v0（如未尝试） -> Vineflower -> CFR -> Procyon -> JADX |
| `accurate` | JD-Core v0（如未尝试） -> Vineflower -> CFR -> Procyon -> Fernflower -> JADX |
| `debuggable` | JD-Core v0（如未尝试） -> Procyon -> CFR -> Vineflower -> Fernflower -> JADX |

`lineNumbers` 是元数据映射，不等于把数字写进 `.java`；要在源码文本中显示行号，用 `renderLineNumbers=decompiled|source|both`。`debuggable` profile 默认更偏向保留调试信息，普通输出不要主动加可见行号，除非用户要求。

## 默认参数策略

| 参数 | 默认策略 |
|------|----------|
| `--json` | CLI 中需要机器解析时加；只给用户看文本时可不加。 |
| `engine` | 默认不传，让工具使用 `auto`。用户指定引擎时严格尊重。 |
| `profile` | 默认 `fast`；审计或结果可疑时用 `accurate`；调试行映射时用 `debuggable`。 |
| `attemptTimeoutMillis` | 默认 30000，一般无需显式传；引擎频繁超时可调大；`0` 禁用超时 |
| `releaseVersion` | 多版本 JAR 或 JMOD 需要时指定；否则用运行时版本。 |
| `limit` / `classLimit` / `jarLimit` / `maxNodes` | 不要随意传 `0` 或超大值；部分批量工具中 `0` 是不限。 |
| `scopePath` | 跨归档查询时必须传；目录 scope 建议同时传 `scopeRecursive=true`。 |
| `indexPath` | 工具默认 `~/.jd-mcp-duo/index.sqlite`；优先主动传 `./.jd-mcp-duo/index.sqlite` 放项目目录下，隔离不同项目且不占系统盘。 |
| `output` / `outputDir` / `saveTo` | 工具会创建目录并可能覆盖已有文件；代理必须先检查目标目录或选唯一输出目录。 |

## 查询模式判定

| 查询内容 | `queryMode` |
|----------|-------------|
| 普通类名、包名、方法名、文件名片段 | `plain` |
| 用户使用 `*` 或 `?` | `wildcard` |
| 用户明确说正则，或 query 明显是正则表达式 | `regex` |

Java 包名里的 `.` 是普通文本，不要因此切到 `regex`。正则中匹配字面 `.` 写 `[.]`。

## 输出规范与安全边界

- 已有源码优先直接读取，不反编译。
- 先缩小范围再批量导出，最好不要直接对用户项目根目录执行无界递归导出。
- 输出目录建议 `./jd-mcp-duo-output/{artifact-name}/`，或用户明确指定的目录。
- 不要输出到 jd-mcp-duo 安装目录、`target/` 构建产物目录、系统临时根目录。
- 如果目标目录已存在且非空，先复用已存在源码或询问是否覆盖；用户明确要求覆盖时再执行。
- `PathSupport` 会拒绝空路径、控制字符和任何 `..` 路径段；遇到 `Invalid or unsafe path` 时要求用户给规范绝对路径或项目内相对路径。
- 私有 Maven 仓库凭证只在用户明确提供时使用；不要把 token 写进答复。

## 结果呈现规范

| 工具类型 | 呈现方式 |
|----------|----------|
| 单类反编译 | 展示源码或关键片段，标注 `engineUsed`、`patched`、`metadataLimited`。 |
| 批量导出 | 汇总成功/失败/跳过数量、输出目录、失败类前几项。 |
| 搜索 | 列匹配项、类型、所属归档、签名或资源路径；控制数量。 |
| 调用链 | 用缩进树或 Mermaid 展示方向、深度、截断状态。 |
| 元数据/依赖 | 表格化，保留坐标、版本、类/方法签名。 |

反编译结果要标注来源，例如：`来源: 反编译（jd-mcp-duo auto, engineUsed=vineflower）`。

## 常见问题

| 问题 | 处理 |
|------|------|
| 类找不到 | 先 `search_in_jar` 或 `type_lookup` 搜完整类名；注意内部名和点分名都可归一化。 |
| 方法重载歧义 | 补 `descriptor`，只写参数和返回值，不写方法名。 |
| 调用链/引用不完整 | 加 `scopePath`、`scopeRecursive=true`、项目本地 `indexPath`，并增大 `depth`/`maxNodes`。 |
| 反编译质量差 | 改 `profile=accurate`，或指定 `engine=vineflower` / `cfr` 对比。 |
| JD-Core v1 栈溢出或卡住 | 优先用 `auto` 和 `java -Xss10m`；仍失败时指定 `vineflower` 或 `cfr`。当前是线程级超时，不是子进程隔离，JVM 致命崩溃仍可能终止进程。 |
| 输出里没有可见行号 | `lineNumbers=true` 只返回映射元数据；需要写进源码用 `renderLineNumbers=decompiled|source|both`。 |
| 搜索无结果 | 确认 `type` 和 `queryMode`；通配符必须用 `queryMode=wildcard`。 |
| MCP 初始化失败 | 只有握手、`initialized` 或 `tools/list` 失败才全局切 CLI；工具参数错误先修参数。 |
| Missing required parameter | 查 `references/tools.md` 或 `<tool> --help` 补必填参数。 |
