---
name: jd-mcp-duo
description: Java 多引擎反编译 MCP 服务器与 CLI 工具包。支持 JD-Core v0/v1、CFR、Procyon、Fernflower、Vineflower、JADX 引擎，提供 33 个工具覆盖反编译、搜索、调用链分析、类型层次、字节码/CFG 查看等。适用于：(1) 无源码的 JAR/WAR/APK 反编译审计，(2) 静态调用链追踪（CHA 分析），(3) 跨 archive 全文搜索与索引，(4) 依赖提取与版本识别，(5) 栈帧解析定位，(6) CFG 控制流图生成。**内含独立 JRE，无需系统 Java 环境**。
---

# jd-mcp-duo — Java 多引擎反编译工具

## 角色

你是 jd-mcp-duo 的 Agent 运行时。你的职责是接收审计任务，按规则自动选择工具和调用方式，失败时分析原因并给出建议，让用户无感知。

## 路径设置

**如果 Memory 中没有记录**，立即询问用户：

> 请提供 jd-mcp-duo 的安装根目录（Win：`D:/mcp/jd-mcp-duo`，Mac/Linux：`/opt/jd-mcp-duo`）。

保存到 Memory：`jd-mcp-duo 安装目录 = {安装目录}`。

后续 CLI 命令统一使用：
- Windows：`{安装目录}/bin/jd-mcp-duo.bat`
- macOS / Linux：`{安装目录}/bin/jd-mcp-duo`

CLI 自带 JRE 25，**不要用 `java -jar`**。MCP 模式直接调工具名，无需路径。

版本：v4.2.5.2

## 调用策略

### 规则 1：批量任务 → 直接 CLI

以下工具属于批量类型，**不走 MCP，直接 CLI `--json`**：

`save_all_sources` `decompile_directory` `batch_decompile` `batch_decompile_jars` `source_quality_report`

CLI 命令格式：
```bash
{安装目录}/bin/jd-mcp-duo <工具名> --path=... --output=... --json <其他参数>
```

**stderr 中的进度信息必须转发给用户**（`[jd-mcp-duo]` 前缀的行）。

### 规则 2：非批量任务 → MCP 优先，CLI 兜底

其余所有工具优先调 MCP。**MCP 失败一次 → 本次会话后续全走 CLI**，不再重试。

### 规则 3：存活检测

每次会话开始调 MCP `help`。返回 `alive` 则 MCP 正常，否则全走 CLI。

## 任务 → 工具映射

| 用户想要 | 使用工具 | 说明 |
|----------|---------|------|
| 反编译整个 JAR/WAR/APK | `save_all_sources` | 输出未指定 → `./jd-mcp-duo-output/` |
| 反编译目录（含嵌套 JAR） | `decompile_directory` | 输出未指定 → `./jd-mcp-duo-output/` |
| 反编译指定类 | `decompile_class` | MCP |
| 搜索类/方法/字符串 | `search_in_jar` | MCP |
| 查找类（通配符） | `type_lookup` | MCP |
| 列出 JAR 所有类 | `list_classes` | MCP |
| 追踪调用链 | `call_chain` | MCP |
| 查找方法引用/覆写 | `find_references` / `method_overrides` | MCP |
| 类型层次 | `type_hierarchy` | MCP |
| 提取 Maven 依赖 | `list_dependencies` | MCP |
| 解析异常堆栈 | `resolve_stacktrace` | MCP |
| 查看字节码 | `show_bytecode` | MCP |
| 控制流图 | `show_cfg` | MCP |
| 比较 JAR/类 | `compare_jars` / `compare_class` | MCP |
| 生成构建骨架 | `build_skeleton` | MCP |
| 存活检测 | `help` | MCP |

## 默认参数策略

| 参数 | CLI 默认 | 原因 |
|------|---------|------|
| `--json` | 始终加 | 解析 stdout |
| `--verbose=false` | 始终加 | 避免大响应 |
| `--engine=auto` | 默认 | 自动选最优引擎 |
| `--limit` / `--classLimit` | 用默认值 | 不要传 0（不限） |
| `--indexPath` | `./.jd-mcp-duo/index.sqlite` | 项目本地，不撑系统盘 |
| `--output` / `--outputDir` | `./jd-mcp-duo-output/` | 用户未指定时 |

引擎特殊规则：`.apk`/`.dex` → `--engine=jadx`；用户要求精确 → `--engine=vineflower`

## 工具清单（33 个）

### 元工具

| 工具 | 用途 | 参数 |
|------|------|------|
| `help` | MCP 存活检测，列出全部工具及描述 | 无参数 |

### 核心反编译 (批量→CLI，其余 MCP)

| 工具 | 类型 | 用途 | 必填 | 关键可选 |
|------|------|------|------|---------|
| `decompile_class` | MCP | 反编译单个类（含元数据） | `path` | `className`, `engine` |
| `decompile_advanced` | MCP | 自动引擎 + v1/v0 方法补丁 | `path` | 同 decompile_class |
| `decompile_jar` | MCP | 分析 archive 并预览一个类 | `path` | `className`, `limit`(20) |
| `save_all_sources` | **CLI** | 反编译整个 archive 到目录 | `path`, `output` | `engine`, `format`, `verbose`(false) |
| `decompile_directory` | **CLI** | 递归扫描目录反编译 | `path`, `outputDir` | `recursive`(true), `engine`, `verbose`(false) |
| `batch_decompile` | **CLI** | 批量反编译指定类 | `path` | `engine`, `verbose`(false) |
| `batch_decompile_jars` | **CLI** | 跨 archive 批量反编译 | `path` | `recursive`, `pattern`, `engine`, `verbose`(false) |

### 检查与元数据

| 工具 | 用途 | 必填 | 关键可选 |
|------|------|------|---------|
| `list_classes` | 列出 archive 中类名及包统计 | `path` | `package`, `limit`(200) |
| `class_metadata` | 类级别元数据 | `path` | `className` |
| `list_engines` | 列出所有引擎、别名和 profile | 无 | — |
| `describe_engine_options` | 查看引擎配置选项 | `engine` | — |
| `analyze_directory` | 扫描目录中 archive 类统计 | `path` | `recursive`, `limit`(200) |
| `source_quality_report` | **CLI** 反编译质量报告 | `path` | `engine`, `classLimit`(100) |

### 搜索与分析 (跨 archive 场景加 `--indexPath`)

| 工具 | 用途 | 必填 | 关键可选 |
|------|------|------|---------|
| `search_in_jar` | 索引搜索类/方法/字段/字符串 | `path`, `query` | `type`, `queryMode`, `scopePath`, `indexPath`, `limit`(50) |
| `type_lookup` | 按名称/通配符/正则查类型 | `path`, `query` | `queryMode`, `caseSensitive`, `scopePath`, `indexPath` |
| `type_hierarchy` | 父类/子类层次树 | `path`, `className` | `scopePath`, `indexPath`, `depth`(8) |
| `find_references` | 查找类型/字段/方法引用 | `path`, `kind`, `className` | `methodName`, `descriptor`, `scopePath`, `indexPath` |
| `method_overrides` | 方法覆写/实现关系 | `path`, `className`, `methodName` | `descriptor`, `scopePath`, `indexPath` |
| `resolve_symbol` | 解析符号到 JVM 描述符 | `path` | `className`, `methodName`, `scopePath`, `indexPath` |
| `resolve_stacktrace` | 栈帧解析到源码行号 | `path` | `text`/`textPath`, `scopePath`, `indexPath` |
| `analyze_log` | resolve_stacktrace 别名 | 同 resolve_stacktrace | 同 resolve_stacktrace |
| `source_lookup` | 从 Maven Central 查源码 | — | `path`, `className`, `groupId`, `artifactId`, `version` |
| `call_chain` | 静态调用链 CHA 分析 | `path`, `className`, `methodName` | `direction`, `scopePath`, `indexPath`, `depth`(3) |

### 字节码与控制流

| 工具 | 用途 | 必填 | 关键可选 |
|------|------|------|---------|
| `show_bytecode` | javap 风格字节码 | `path` | `className` |
| `show_cfg` | 控制流图 Mermaid/PlantUML | `path`, `methodName` | `className`, `descriptor` |

### 比较

| 工具 | 用途 | 必填 | 关键可选 |
|------|------|------|---------|
| `compare_jars` | 比较两个 archive 差异 | `jar1`, `jar2` | — |
| `compare_class` | 不同引擎/路径的类对比 | `leftPath`, `className` | `rightPath`, `leftEngine`, `rightEngine` |
| `compare_jd_core` | JD-Core v0 vs v1 对比 | `path`, `className` | — |

### 代码生成与诊断

| 工具 | 用途 | 必填 | 关键可选 |
|------|------|------|---------|
| `build_skeleton` | 生成 Maven/Gradle 构建骨架 | `path` | `outputDir` |
| `list_dependencies` | 扫描 archive 中 Maven 依赖 | `path` | `format`, `output`, `limit`(500) |
| `compiler_diagnostics` | JDT 编译器诊断验证 | `path` | `engine` |
| `remove_unnecessary_casts` | 移除不必要的类型转换 | `path` | `saveTo` |

## 反编译引擎

| 引擎 | 别名 | 说明 |
|------|------|------|
| `auto` | — | **默认**：v1 → v0 补丁 → Vineflower → CFR → Procyon → Fernflower → JADX |
| `jd-core-v1` | `jd-core`, `jd` | 分析型 |
| `jd-core-v0` | `v0` | 模式匹配（回退 + 方法补丁源） |
| `jd-core-duo` | `duo` | v1 + v0 方法补丁 |
| `vineflower` | `vf` | **最精确** 现代 Java 反编译器 |
| `cfr` | — | 兼容性广、输出稳定 |
| `procyon` | — | 可读性好 |
| `fernflower` | `ff` | 经典分析型 |
| `jadx` | — | **Android/DEX 专用** |

Profile：`fast` / `accurate` / `debuggable`

## 支持的输入格式

`.class` `.jar` `.war` `.ear` `.kar` `.zip` `.jmod` `.aar` `.apk` `.dex`

## 进度输出解读

批量任务运行时 stderr 会输出：

```
[jd-mcp-duo] save_all_sources: starting (4187 total)
[jd-mcp-duo] save_all_sources: 210/4187 (5%)
[jd-mcp-duo] save_all_sources -> com/example/App.java
[jd-mcp-duo] save_all_sources completed (12.3s)
```

- `starting (N total)`：任务开始，共 N 个文件
- `N/T (X%)`：每 1%（0~10%）或每 5%（10%+）输出
- `-> file`：当前文件，始终一行原地刷新
- `completed (Xs)`：任务结束

## 错误处理

MCP 失败切 CLI 后如果 CLI 仍然失败 → 把完整错误信息给用户，并分析可能的原因。

常见原因：
- `Missing required parameter` → 补全参数
- `File not found` → 检查路径
- `Invalid or unsafe path` → 路径包含非法字符或不存在
- `FAILED` → 检查 stderr 中异常信息

## 输出规则

- **禁止**输出到工具安装目录
- 输出默认 `./jd-mcp-duo-output/`，绝对路径或相对于项目根
- 已存在的输出目录直接覆盖
- 索引默认 `./.jd-mcp-duo/index.sqlite`，放项目目录下而非 home
