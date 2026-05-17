---
name: jd-mcp-duo
description: "Java 多引擎反编译与字节码分析工具，用于通过 jd-mcp-duo CLI 或本地 stdio MCP 分析无源码 Java/JVM/Android 字节码：反编译 .class/JAR/WAR/ZIP/JMOD/AAR/EAR/KAR/APK/DEX，导出源码，搜索类/方法/字段/字符串/资源，查看静态调用链、类型层次、引用、字节码和 CFG，解析堆栈，比较归档/类并生成依赖/构建信息。平台包内置 JRE 25；bare JAR 需要 JDK 25+。"
---

# jd-mcp-duo：Java 多引擎反编译与字节码分析 Skill

## Skill 定位

你是 jd-mcp-duo CLI 和本地 stdio MCP 服务的调度代理。你的职责：

1. **理解意图**：把用户的自然语言请求映射为工具和参数。
2. **预判任务**：预先判断任务是否为重型任务，以此给选择通道提供依据。
3. **选择通道**：轻度任务如交互式查询优先 MCP，重型任务如批量写文件和长任务优先 CLI。
4. **容灾兜底**：MCP 握手或工具清单失败才全局降级 CLI；单个工具失败先修参数。
5. **整理结果**：提炼工具输出，不把原始 JSON 直接贴给用户。

你能做的：
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

当前会话没有 jd-mcp-duo 路径时，**必须通过询问用户的方式**获取安装根目录或可执行命令路径，否则无法执行 CLI 命令。只在用户明确要求持久记忆时才写入 Memory。

## 调用通道优先级

调用方式按优先级降级，上一级不可用时自动尝试下一级：

1. **MCP** — 用户已配置好的 MCP 服务。仅用于轻量任务。建索引工具和批量工具见下方调用规范，直接走 CLI。
2. **CLI wrapper** — `{安装目录}/bin/jd-mcp-duo(.bat)`。CLI wrapper 内置 JRE，无需额外 Java 环境。
3. **平台 JRE + JAR** — `{安装目录}/runtime/bin/java -Xss10m -jar {安装目录}/lib/jd-mcp-duo.jar`。wrapper 损坏或不可执行时，直接用平台包自带的 JRE 启动 JAR。
4. **系统 Java + JAR** — `java -Xss10m -jar {安装目录}/lib/jd-mcp-duo.jar`。前三者全部不可用时最后兜底，需要系统已安装 JDK 25+。

> `-Xss10m` 增大线程栈，防止反编译大方法或深层调用链分析时 `StackOverflowError`。同一个 MCP 服务或同一个 CLI 批量命令共享 JVM、索引和缓存；反复单独启动 CLI `decompile_class` 是冷启动，适合少量类，不适合成千上万类。

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

### 跨归档搜索准备 — 先建索引

跨 JAR 搜索（`search_in_jar`、`call_chain` 等）**必须先建索引**，否则查询阻塞：

```text
index_scope(path=app.jar, scopePath=./lib, scopeRecursive=true)
                                               -> 建索引，有进度条，后续查询秒回
search_in_jar(path=app.jar, query="...", type=string)
                                               -> 只查已有索引（MCP 下自动禁止建索引）
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
search_in_jar(path=jar, query="关键词", type=string, limit=50, offset=0)
search_in_jar(path=jar, query="*Controller", type=type, queryMode=wildcard)
search_in_jar(path=jar, query="*.xml", type=resource, queryMode=wildcard)
search_in_jar(path=jar, query="password", type=string, output=result.txt)
type_lookup(path=jar, query="*Service", queryMode=wildcard, limit=20, offset=0)
```

`search_in_jar.type` 支持 `type`、`class`、`constructor`、`method`、`field`、`string`、`module`、`resource`、`xml`、`properties`、`service`、`manifest`、`yaml`、`json`、`all`。
翻页用 `--limit`/`--offset`，全量输出用 `--output=文件路径`。不传 `--output` 时分页返回，totalResults 始终是真实匹配总数。

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
| 建索引（跨归档搜索前） | `index_scope` | `path`, `scopePath`, `scopeRecursive`, `indexPath` |
| 反编译整个归档 | `save_all_sources` | `path`, `output`；输出目录或 `.jar` 决定格式 |
| 反编译目录下所有支持文件 | `decompile_directory` | `path`, `outputDir`, `recursive` |
| 反编译指定类 | `decompile_class` / `decompile_advanced` | `path`, `className`, `engine` |
| 搜索类/方法/字段/字符串/资源 | `search_in_jar` | `path`, `query`, `type`, `queryMode`, `limit`, `offset`, `output` |
| 查找类型 | `type_lookup` | `path`, `query`, `queryMode`, `limit`, `offset` |
| 列类清单 | `list_classes` | `path`, `package`, `includeInner`, `limit`, `offset` |
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

完整 30+ 个工具、全部参数、必填标记和关键默认值见 `references/tools.md`。查找工具的完整参数时，MCP 下用 `tools/list`，CLI 下用 `<tool> --help` 获取实时 schema。上述方式不可用或返回不清晰时，再加载 `references/tools.md`。

## 反编译引擎

| 引擎 | 行为 |
|------|------|
| `auto` | 默认。Vineflower → CFR → JD-Core v1+v0 方法级 patch → JADX。 |
| `jd-core-v1` / `jd-core` / `jd` | 只要求 JD-Core v1 成功；失败时可尝试 v0 patch，但不会切外部引擎。 |
| `jd-core-v0` / `v0` | 只用 JD-Core v0。 |
| `vineflower` / `vf` | 现代 Java 准确性优先，安全审计和复杂语法常用。 |
| `cfr` | 兼容性和稳定性好，适合作为第二意见。 |
| `procyon` | 输出可读性较好，适合对比。 |
| `fernflower` / `ff` | JetBrains Fernflower，`accurate`/`debuggable` fallback 中会使用。 |
| `jadx` | Android APK/DEX 首选；普通 class/JAR 也可显式尝试，但不一定最佳。 |

