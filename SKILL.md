---
name: jd-mcp-duo
description: Java 多引擎反编译 MCP 服务器与 CLI 工具包。支持 JD-Core v0/v1、CFR、Procyon、Fernflower、Vineflower、JADX 引擎，提供 33 个工具覆盖反编译、搜索、调用链分析、类型层次、字节码/CFG 查看等。适用于：(1) 无源码的 JAR/WAR/APK 反编译审计，(2) 静态调用链追踪（CHA 分析），(3) 跨 archive 全文搜索与索引，(4) 依赖提取与版本识别，(5) 栈帧解析定位，(6) CFG 控制流图生成。**内含独立 JRE，无需系统 Java 环境**。
---

# jd-mcp-duo — Java 多引擎反编译 Agent Skill

## 角色

你是 jd-mcp-duo 的 Agent 运行时。职责：接收审计任务 → 按规则选工具和调用方式 → 失败时分析原因给建议。用户描述需求即可，不必关心底层用什么工具。

## 路径设置

**如果 Memory 未记录**，先问：

> 请提供 jd-mcp-duo 的安装根目录（Win：`D:/mcp/jd-mcp-duo`，Mac/Linux：`/opt/jd-mcp-duo`）。

保存到 Memory `jd-mcp-duo 安装目录 = {安装目录}`。

CLI 命令：
- Win：`{安装目录}/bin/jd-mcp-duo.bat`
- Mac/Linux：`{安装目录}/bin/jd-mcp-duo`

CLI 自带 JRE 25，**不要用 `java -jar`**。MCP 模式直接调工具名。

版本：v4.2.5.2

---

## MCP / CLI 调用契约

| 规则 | 内容 |
|------|------|
| **批量→CLI** | `save_all_sources` `decompile_directory` `batch_decompile` `batch_decompile_jars` `source_quality_report` 直接 CLI，不走 MCP |
| **非批量→MCP** | 其余工具 MCP 优先；MCP 失败一次 → 本次会话后续全走 CLI |
| **存活检测** | 会话开始调 MCP `help`，失败则全走 CLI |
| **CLI 命令** | `{安装目录}/bin/jd-mcp-duo <工具名> --key=value --json`，参数 `--key=value` 或 `--key value`，重复 key=数组，无值=布尔 true |
| **CLI 输出** | stdout=JSON（`text`/`structuredData`/`isError`），stderr=进度+日志 |
| **超时** | 单次反编译传 `attemptTimeoutMillis=30000` |
| **进度转发** | stderr 中 `[jd-mcp-duo]` 前缀行必须转发给用户 |

> **性能差异**：批量工具共享单个 JVM，效率高数倍；`decompile_class` 每次启动独立 JVM。大量文件先收窄范围再用批量方式，禁止对项目根做无界导出。

---

## 审计工作流（按场景选择策略）

### 策略 A：按需反编译（逐类深入）

```
1. list_classes(path=jar)           → 浏览归档结构
2. decompile_class(path=jar, className=入口类)  → 反编译 Controller
3. 阅读反编译结果，识别依赖类
4. decompile_class(path=jar, className=依赖类)  → 按需反编译
```

> **源码优先**：已有 `.java` 文件时直接读取，不重复反编译。已反编译结果缓存到 `{output_path}/decompiled/`。

### 策略 B：调用链优先（从入口到 Sink）

```
1. call_chain(path=jar, className=入口, methodName=方法, direction=callees, depth=5)
                                    → 追踪完整调用路径
2. decompile_class 反编译调用链中的关键类
3. 对可疑节点深入分析（show_bytecode / show_cfg）
```

跨 JAR 追踪时加 `scopePath={依赖目录}` `scopeRecursive=true`。

### 策略 C：搜索定位（关键字/模式驱动）

```
1. search_in_jar(path=jar, query="关键字", type=string/method)
                                    → 搜 SQL 片段、URL、密钥
2. search_in_jar(path=jar, query="*Controller", type=type, queryMode=wildcard)
                                    → 搜类名模式
3. find_references(path=jar, className=危险类, kind=method, methodName=方法)
                                    → 找谁调用了危险函数
```

---

## 任务 → 工具映射

| 用户意图 | 工具 | 关键参数 |
|----------|------|---------|
| 反编译整个 JAR/WAR/APK | `save_all_sources` | `path`, `output`(默认`./jd-mcp-duo-output/`) |
| 反编译目录（含嵌套 JAR） | `decompile_directory` | `path`, `outputDir`(默认`./jd-mcp-duo-output/`) |
| 反编译指定类 | `decompile_class` | `path`, `className` |
| 搜索类/方法/字段/字符串 | `search_in_jar` | `path`, `query`, `type`, `queryMode` |
| 按名称/通配符查类型 | `type_lookup` | `path`, `query`, `queryMode` |
| 列出 JAR 类清单 | `list_classes` | `path`, `package`(过滤) |
| 追踪调用链 | `call_chain` | `path`, `className`, `methodName`, `direction`, `depth` |
| 查找方法引用 | `find_references` | `path`, `kind`, `className`, `methodName` |
| 方法覆写关系 | `method_overrides` | `path`, `className`, `methodName` |
| 类型层次 | `type_hierarchy` | `path`, `className` |
| 提取依赖列表 | `list_dependencies` | `path`, `format` |
| 解析异常堆栈 | `resolve_stacktrace` | `path`, `text`/`textPath` |
| 查看字节码 | `show_bytecode` | `path`, `className` |
| 控制流图 | `show_cfg` | `path`, `className`, `methodName` |
| 比较 JAR/类 | `compare_jars` / `compare_class` | `jar1`, `jar2` / `leftPath`, `className` |
| 构建骨架 | `build_skeleton` | `path`, `outputDir` |
| 存活检测 | `help` | 无 |

---

## 查询模式规则

| 查询内容 | `queryMode` |
|----------|-------------|
| 精确匹配（默认） | `plain` |
| 含 `*` `?` 通配符 | `wildcard` |
| 含 `\|` `.` `()` `[]` `+` `^` `$` 等正则语法 | `regex` |

> 正则中匹配字面 `.` 写 `[.]`，字面括号写 `[(]` / `[)]`。

---

## 默认参数策略

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `--json` | 始终 | CLI 解析 stdout |
| `--verbose` | `false` | 批量工具只返回计数 |
| `--engine` | `auto` | 多引擎自动回退 |
| `--attemptTimeoutMillis` | `30000` | 单类反编译超时 |
| `--limit` / `--classLimit` | 工具默认值 | **不要传 0**（不限，会撑爆上下文） |
| `--indexPath` | `./.jd-mcp-duo/index.sqlite` | 放项目本地，不撑系统盘 |
| `--output` / `--outputDir` | `./jd-mcp-duo-output/` | 用户未指定时，已存在则覆盖 |
| `--scopePath` | 跨 JAR 时必须传 | 指向依赖归档目录 |

---

## 引擎与 Profile 选择

| 引擎 | 适用场景 |
|------|---------|
| `auto`（默认） | **通用**，v1→v0补丁→Vineflower→CFR→Procyon→Fernflower→JADX 依次回退 |
| `vineflower` | **追求准确**，最精确的现代 Java 反编译器 |
| `cfr` | 兼容性广、输出稳定 |
| `jadx` | **Android APK/DEX 专用** |
| `procyon` | 备选，可读性好 |

| Profile | 场景 |
|---------|------|
| `fast` | 快速浏览，速度优先 |
| `accurate` | **安全审计推荐**，准确性优先 |
| `debuggable` | 调试，保留最多信息 |

---

## 工具清单（33 个）

### 元工具

| 工具 | 用途 | 参数 |
|------|------|------|
| `help` | MCP 存活检测 + 工具列表 | 无 |

### 核心反编译

| 工具 | 类型 | 用途 | 必填 | 关键可选 |
|------|------|------|------|---------|
| `decompile_class` | MCP | 反编译单个类（含元数据） | `path` | `className`, `engine`, `attemptTimeoutMillis`(30000) |
| `decompile_advanced` | MCP | 自动引擎 + v1/v0 方法补丁 | `path` | `advancedLookup`, `classpath` |
| `decompile_jar` | MCP | 分析 archive + 预览类 | `path` | `className`, `limit`(20) |
| `save_all_sources` | **CLI** | 反编译整个 archive 到目录 | `path`, `output` | `format`, `engine` |
| `decompile_directory` | **CLI** | 递归扫描目录反编译 | `path`, `outputDir` | `recursive`(true), `engine` |
| `batch_decompile` | **CLI** | 目录中批量反编译 | `path` | `engine`, `limit` |
| `batch_decompile_jars` | **CLI** | 目录中批量反编译归档 | `path` | `recursive`, `pattern`, `jarLimit`, `classLimit` |

### 检查与元数据

| 工具 | 用途 | 必填 | 关键可选 |
|------|------|------|---------|
| `list_classes` | 列出 archive 中类名及包统计 | `path` | `package`, `limit`(200) |
| `class_metadata` | 类级元数据（方法/字段/注解/版本） | `path` | `className` |
| `list_engines` | 列出所有引擎、别名、profile | 无 | — |
| `describe_engine_options` | 查看引擎可配置参数 | `engine` | — |
| `analyze_directory` | 目录中 archive 的类统计 | `path` | `recursive`, `limit`(200) |
| `source_quality_report` | **CLI** 反编译质量报告 | `path` | `engine`, `classLimit`(100) |

### 搜索与分析（跨 archive 必须传 `scopePath` + `indexPath`）

| 工具 | 用途 | 必填 | 关键可选 |
|------|------|------|---------|
| `search_in_jar` | 索引搜索类/方法/字段/字符串/资源 | `path`, `query` | `type`, `queryMode`, `scopePath`, `indexPath`, `limit`(50) |
| `type_lookup` | 按名/通配符/正则查类型 | `path`, `query` | `queryMode`, `caseSensitive`, `scopePath`, `indexPath` |
| `type_hierarchy` | 父类/子类层次树 | `path`, `className` | `scopePath`, `indexPath`, `depth`(8) |
| `find_references` | 查找类型/字段/方法引用 | `path`, `kind`, `className` | `methodName`, `descriptor`, `scopePath`, `indexPath` |
| `method_overrides` | 方法覆写/实现关系 | `path`, `className`, `methodName` | `descriptor`, `scopePath`, `indexPath` |
| `resolve_symbol` | 解析符号到 JVM 描述符 | `path` | `className`, `methodName`, `scopePath`, `indexPath` |
| `resolve_stacktrace` | 栈帧解析到源码行号 | `path` | `text`/`textPath`, `scopePath`, `indexPath` |
| `analyze_log` | 同 resolve_stacktrace | 同上 | 同上 |
| `source_lookup` | 从 Maven Central 查原始源码 | — | `path`, `className`, `groupId`, `artifactId`, `version` |
| `call_chain` | **CHA 静态调用链**（BFS） | `path`, `className`, `methodName` | `direction`, `scopePath`, `indexPath`, `depth`(3), `maxNodes`(128) |

### 字节码与控制流

| 工具 | 用途 | 必填 | 关键可选 |
|------|------|------|---------|
| `show_bytecode` | javap 风格字节码 | `path` | `className` |
| `show_cfg` | 控制流图（Mermaid/PlantUML） | `path`, `methodName` | `className`, `descriptor` |

### 比较

| 工具 | 用途 | 必填 | 关键可选 |
|------|------|------|---------|
| `compare_jars` | 两归档差异（增/删/改） | `jar1`, `jar2` | — |
| `compare_class` | 不同引擎/路径类对比 | `leftPath`, `className` | `rightPath`, `leftEngine`, `rightEngine` |
| `compare_jd_core` | JD-Core v0 vs v1 | `path`, `className` | — |

### 代码生成与诊断

| 工具 | 用途 | 必填 | 关键可选 |
|------|------|------|---------|
| `build_skeleton` | 生成 Maven/Gradle 构建文件 | `path` | `outputDir` |
| `list_dependencies` | 扫描 archive 中嵌入式 Maven 依赖 | `path` | `format`, `output`, `limit`(500) |
| `compiler_diagnostics` | JDT 编译器诊断 | `path` | `engine` |
| `remove_unnecessary_casts` | 移除不必要类型转换 | `path` | `saveTo` |

---

## 提示词配置

提示词中不可出现 `--descriptor=(...)V`、`--descriptor=<init>`、`--descriptor=<clinit>` 等尖括号括起的描述符。必须使用单引号字面量，例如 `--descriptor='()V'`、`--descriptor='<init>'`、`--descriptor='<clinit>'`。

## 进度输出解读

批量任务运行时 stderr 输出：

```
[jd-mcp-duo] save_all_sources: starting (4187 total)
[jd-mcp-duo] save_all_sources: 210/4187 (5%)
[jd-mcp-duo] save_all_sources -> com/example/App.java
[jd-mcp-duo] save_all_sources completed (12.3s)
```

- `starting (N total)`：共 N 个文件，开始处理
- `N/T (X%)`：0~10% 每 1% 一次，之后每 5%
- `-> file`：当前正在处理的文件，始终一行原地刷新
- `completed (Xs)`：完成

## 输出规则

- **禁止**输出到工具安装目录
- 输出默认 `./jd-mcp-duo-output/`，已存在直接覆盖
- 索引默认 `./.jd-mcp-duo/index.sqlite`，放项目本地
- 审计报告中标注反编译来源：`来源: 反编译（jd-mcp-duo auto）`

## 常见问题

| 问题 | 解决 |
|------|------|
| 类找不到 | `search_in_jar` 搜关键字确认完整类名 |
| 方法重载分不清 | 加 `descriptor` 指定 JVM 描述符 |
| 调用链不完整 | 增大 `depth`/`maxNodes`，或加 `scopePath` 包含依赖 JAR |
| 反编译质量差 | `engine=vineflower` 或 `profile=accurate` |
| 跨 JAR 调用找不到 | 加 `scopePath` 指向依赖目录 + `scopeRecursive=true` |
| 搜索无结果 | 确认 `queryMode`：`\|`→regex，`*?`→wildcard |
| MCP 失败 | 自动切 CLI，不重试 |
| Missing required parameter | 补全必填参数 |
| Invalid or unsafe path | 路径含非法字符或不存在 |
