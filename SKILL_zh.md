---
name: jd-mcp-duo
description: Java 多引擎反编译 MCP 服务器与 CLI 工具包。支持 JD-Core v0/v1、CFR、Procyon、Fernflower、Vineflower、JADX 反编译引擎，提供 33 个工具覆盖反编译、搜索、调用链分析、类型层次、字节码/CFG 查看等。适用于：(1) 无源码的 JAR/WAR/APK 反编译审计，(2) 静态调用链追踪（CHA 分析），(3) 跨 archive 全文搜索与索引，(4) 依赖提取与版本识别，(5) 栈帧解析定位，(6) CFG 控制流图生成。**内含独立 JRE，无需系统 Java 环境**。
---

# jd-mcp-duo — Java 多引擎反编译工具

## 快速存活检测

MCP 模式下调用 `help` 确认服务是否正常运行：

```
help
→ jd-mcp-duo mcp server is alive. 33 tools available: decompile_class, ...
```

## 安装位置

```
工具目录: D:/tools/tools/java/jd-mcp-duo/
CLI 入口: D:/tools/tools/java/jd-mcp-duo/bin/jd-mcp-duo.bat
JAR 文件: D:/tools/tools/java/jd-mcp-duo/lib/jd-mcp-duo.jar
版本: v4.2.5.2 (内置 JRE 25，无需系统 Java)
```

## 使用方式

### CLI 模式（推荐用于脚本和 agent 调用）

```bash
# 基本格式
D:/tools/tools/java/jd-mcp-duo/bin/jd-mcp-duo.bat <tool-name> [options]

# 或者直接用 jar（需要 JDK 25+，大 archive 建议加 -Xss10m）
java -Xss10m -jar D:/tools/tools/java/jd-mcp-duo/lib/jd-mcp-duo.jar <tool-name> [options]

# 查看版本
D:/tools/tools/java/jd-mcp-duo/bin/jd-mcp-duo.bat --version

# 查看帮助
D:/tools/tools/java/jd-mcp-duo/bin/jd-mcp-duo.bat --help

# 查看特定工具帮助
D:/tools/tools/java/jd-mcp-duo/bin/jd-mcp-duo.bat decompile_class --help
```

参数格式：`--key=value` 或 `--key value`。加 `--json` 输出结构化 JSON。

参数错误时自动显示用法示例，例如：

```
Execution failed: Missing required parameter: outputDir
Usage: decompile_directory --path=<path> --outputDir=<outputDir> [options]
See: jd-mcp-duo.jar decompile_directory --help
```

### MCP Server 模式

在 Claude Code 的 MCP 配置中添加：

```bash
claude mcp add --transport stdio --scope user jd-mcp-duo -- D:/tools/tools/java/jd-mcp-duo/bin/jd-mcp-duo.bat
```

或手动编辑 `~/.claude.json`：

```json
{
  "mcpServers": {
    "jd-mcp-duo": {
      "type": "stdio",
      "command": "D:/tools/tools/java/jd-mcp-duo/bin/jd-mcp-duo.bat",
      "args": [],
      "env": {}
    }
  }
}
```

验证：`claude mcp list && claude mcp get jd-mcp-duo`

协议版本：支持 `2025-11-25`、`2025-06-18`、`2025-03-26`、`2024-11-05`。

## 工具清单（33 个）

### 元工具

| 工具 | 用途 | 参数 |
|------|------|------|
| `help` | MCP 存活检测，列出全部工具及描述 | 无参数 |

### 核心反编译

| 工具 | 用途 | 必填参数 | 关键可选参数 |
|------|------|---------|------------|
| `decompile_class` | 反编译单个类（含结构化元数据） | `path` | `className`, `engine`, `profile`, `releaseVersion`, `attemptTimeoutMillis`, `lineNumbers`, `renderLineNumbers`, `writeSidecarMetadata`, `advancedLookup`, `classpath`, `preferences`, `output` |
| `decompile_advanced` | 自动引擎选择 + JD-Core v1/v0 方法级补丁 | `path` | 继承 `decompile_class` 全部参数 |
| `decompile_jar` | 分析 archive 并可选预览一个反编译类 | `path` | `className`, `limit`(默认20), `decompile`(布尔), `engine`, `profile`, `releaseVersion`, `attemptTimeoutMillis`, `lineNumbers`, `renderLineNumbers`, `advancedLookup`, `classpath`, `preferences` |
| `save_all_sources` | 反编译整个 archive 到目录或 sources JAR（**保留目录结构**） | `path`, `output` | `format`(directory/sources-jar), `engine`, `profile`, `releaseVersion`, `attemptTimeoutMillis`, `lineNumbers`, `renderLineNumbers`, `writeSidecarMetadata`, `advancedLookup`, `classpath`, `verbose`(默认false, 控制结构化数据是否包含详情) |
| `decompile_directory` | 递归扫描目录反编译（保留相对路径，JAR 内类名动态显示） | `path`, `outputDir` | `recursive`(默认true), `engine`, `profile`, `releaseVersion`, `attemptTimeoutMillis`, `lineNumbers`, `renderLineNumbers`, `writeSidecarMetadata`, `advancedLookup`, `classpath`, `summaryOnly`, `fileLimit`, `verbose`(默认false) |
| `batch_decompile` | 从目录根反编译指定多个类 | `path` | `engine`, `profile`, `releaseVersion`, `attemptTimeoutMillis`, `lineNumbers`, `renderLineNumbers`, `writeSidecarMetadata`, `advancedLookup`, `classpath`, `limit`, `summaryOnly`, `verbose`(默认false), `outputDir`, `preferences` |
| `batch_decompile_jars` | 跨多个 archive 反编译指定类 | `path` | `recursive`, `pattern`, `engine`, `profile`, `releaseVersion`, `attemptTimeoutMillis`, `lineNumbers`, `renderLineNumbers`, `writeSidecarMetadata`, `advancedLookup`, `classpath`, `classLimit`, `jarLimit`, `summaryOnly`, `verbose`(默认false), `outputDir`, `preferences` |

### 检查与元数据

| 工具 | 用途 | 必填参数 | 关键可选参数 |
|------|------|---------|------------|
| `list_classes` | 列出 archive 中类名及包统计 | `path` | `package`(包前缀过滤), `releaseVersion`, `includeInner`(布尔), `detailed`(布尔), `limit`(默认200) |
| `class_metadata` | 查看类级元数据（访问标志、字节码版本、方法、字段、注解等） | `path` | `className`, `releaseVersion` |
| `list_engines` | 列出所有可用反编译引擎、别名和 profile | 无 | — |
| `describe_engine_options` | 查看引擎配置选项和 profile | `engine` | — |
| `analyze_directory` | 扫描目录中 archive 的类数量和大小 | `path` | `recursive`(布尔), `pattern`(glob), `limit`(默认200) |
| `source_quality_report` | 反编译质量指标报告 | `path` | `engine`, `profile`, `releaseVersion`, `attemptTimeoutMillis`, `lineNumbers`, `advancedLookup`, `classpath`, `classLimit`(默认100, 0=不限) |

### 搜索与分析

| 工具 | 用途 | 必填参数 | 关键可选参数 |
|------|------|---------|------------|
| `search_in_jar` | 跨类/方法/字段/字符串/资源的索引搜索 | `path`, `query` | `type`(type/class/constructor/method/field/string/module/resource/xml/properties/service/manifest/yaml/json/all), `queryMode`(plain/wildcard/regex), `caseSensitive`(布尔), `scopePath`, `scopeRecursive`(布尔), `distinct`(布尔, string去重), `indexPath`, `limit`(默认50) |
| `type_lookup` | 按名称/通配符/正则查找类型声明 | `path`, `query` | `queryMode`(plain/wildcard/regex), `caseSensitive`(布尔), `scopePath`, `scopeRecursive`(布尔), `indexPath`, `limit`(默认50) |
| `type_hierarchy` | 构建父类型/子类型层次树 | `path`, `className` | `scopePath`, `scopeRecursive`(布尔), `indexPath`, `depth`(默认8), `maxNodes`(默认256) |
| `find_references` | 查找类型/字段/方法的所有引用（含 targetDescriptor） | `path`, `kind`, `className` | `fieldName`, `methodName`, `descriptor`, `scopePath`, `scopeRecursive`(布尔), `indexPath`, `depth`(默认1), `maxNodes`(默认256) |
| `method_overrides` | 查找方法覆写/实现关系 | `path`, `className`, `methodName` | `descriptor`, `scopePath`, `scopeRecursive`(布尔), `indexPath`, `depth`(默认8), `maxNodes`(默认256) |
| `resolve_symbol` | 解析类型/字段/方法符号到 JVM 描述符 | `path` | `className`, `fieldName`, `methodName`, `descriptor`, `scopePath`, `scopeRecursive`(布尔), `indexPath` |
| `resolve_stacktrace` | 解析 Java 异常栈帧到反编译源码行号 | `path` | `text`(原始栈帧文本), `textPath`(.log 文件路径), `scopePath`, `scopeRecursive`(布尔), `indexPath`, `engine`, `attemptTimeoutMillis`, `maxFrames`(默认200), `lineMappingLimitPerFrame`(默认1) |
| `analyze_log` | resolve_stacktrace 的别名 | 同 resolve_stacktrace | 同 resolve_stacktrace |
| `source_lookup` | 从 sources JAR 或 Maven Central 查找原始源码 | — | `path`, `className`, `sourceJarPath`, `groupId`, `artifactId`, `version`, `sha1`, `sha1File`, `configPath`, `searchProvider`, `searchBaseUrl`, `remoteContentBaseUrl`, `proxyHost`, `proxyPort`, `username`, `password`, `bearerToken`, `saveTo` |
| `call_chain` | **静态调用链追踪**（CHA 分析，BFS 遍历） | `path`, `className`, `methodName` | `descriptor`, `direction`(callers/callees/both, 默认both), `scopePath`, `scopeRecursive`(布尔), `indexPath`, `depth`(默认3), `maxNodes`(默认128) |

### 字节码与控制流

| 工具 | 用途 | 必填参数 | 关键可选参数 |
|------|------|---------|------------|
| `show_bytecode` | 显示 javap 风格的类字节码 | `path` | `className`, `releaseVersion`, `verbose`(布尔, 默认true, 控制 javap -v) |
| `show_cfg` | 生成方法控制流图（Mermaid/PlantUML） | `path`, `methodName` | `className`, `descriptor`, `format`(mermaid/plantuml/both, 默认mermaid), `releaseVersion` |

### 比较

| 工具 | 用途 | 必填参数 | 关键可选参数 |
|------|------|---------|------------|
| `compare_jars` | 比较两个 archive 的差异（增/删/改） | `jar1`, `jar2` | `detail`(布尔, 默认true) |
| `compare_class` | 比较不同引擎或不同路径的同一类反编译结果 | `leftPath`, `className` | `rightPath`(默认同leftPath), `leftClassName`, `rightClassName`, `leftEngine`, `rightEngine`, `releaseVersion`, `attemptTimeoutMillis`, `lineNumbers`, `advancedLookup`, `classpath` |
| `compare_jd_core` | JD-Core v0 vs v1 的对比 | `path`, `className` | `rightPath`, `rightClassName`, `releaseVersion`, `attemptTimeoutMillis`, `lineNumbers`, `advancedLookup`, `classpath` |

### 代码生成与诊断

| 工具 | 用途 | 必填参数 | 关键可选参数 |
|------|------|---------|------------|
| `build_skeleton` | 从 archive 生成 Maven/Gradle 构建骨架 | `path` | `files`, `outputDir`, `configPath`, `searchProvider`(maven-central/nexus2/nexus3), `searchBaseUrl`, `remoteContentBaseUrl`, `proxyHost`, `proxyPort`, `username`, `password`, `bearerToken` |
| `list_dependencies` | 扫描 archive 中的 Maven 依赖坐标 | `path` | `format`(json/text), `output`(文件路径), `limit`(默认500) |
| `compiler_diagnostics` | Eclipse JDT 编译器诊断验证 | `path` | `className`, `engine`, `profile`, `attemptTimeoutMillis`, `lineNumbers`, `advancedLookup`, `classpath` |
| `remove_unnecessary_casts` | 移除源码中不必要的类型转换 | `path` | `className`, `engine`, `profile`, `attemptTimeoutMillis`, `lineNumbers`, `advancedLookup`, `classpath`, `saveTo` |

## 反编译引擎

| 引擎 | 别名 | 说明 |
|------|------|------|
| `auto` | — | 多引擎回退策略（默认）：JD-Core v1 → v0 补丁 → Vineflower → CFR → Procyon → Fernflower → JADX |
| `jd-core-v1` | `jd-core`, `jdcore`, `jd` | 分析型反编译器 |
| `jd-core-v0` | `jdcore-v0`, `v0` | 模式匹配反编译器（回退 + 方法补丁源） |
| `jd-core-duo` | `jd-duo`, `duo` | v1 + v0 方法补丁 |
| `cfr` | — | 广泛兼容、输出稳定 |
| `procyon` | — | 可读输出，支持行号选项 |
| `fernflower` | `ff` | 经典分析型反编译器 |
| `vineflower` | `vf` | 最精确的现代 Java 反编译器 |
| `jadx` | — | JVM + Android/DEX 导向 |

**Profile**：`fast`、`accurate`、`debuggable` — 选择对应的引擎默认配置和选项。

## 通用参数

| 参数 | 类型 | 适用工具 | 说明 |
|------|------|---------|------|
| `engine` | string | 反编译工具 | 引擎选择（见上表） |
| `profile` | string | 反编译工具 | `fast`、`accurate`、`debuggable` |
| `releaseVersion` | int | 反编译/元数据工具 | 目标多版本 class 版本，默认当前运行时版本 |
| `attemptTimeoutMillis` | int | 反编译工具 | 每次引擎尝试的超时（毫秒），0 禁用 |
| `scopePath` | string | 搜索/分析工具 | 多 archive 作用域索引路径 |
| `scopeRecursive` | boolean | 搜索/分析工具 | scopePath 为目录时递归扫描，默认 false |
| `indexPath` | string | 搜索/分析工具 | 自定义 SQLite 索引路径，默认 `~/.jd-mcp-duo/index.sqlite` |
| `verbose` | boolean | 批量反编译工具 | 结构化结果中包含每个文件的详情，默认 false（减少响应体积） |
| `descriptor` | string | 方法级工具 | JVM 方法描述符（用于重载消歧），如 `(Ljava/lang/String;)V` |
| `classpath` | string/array | 反编译工具 | 额外类路径条目 |
| `advancedLookup` | boolean | 反编译工具 | 搜索同级 archive 解析依赖，默认 false |
| `lineNumbers` | boolean | 反编译工具 | 包含行号元数据，默认 false |
| `renderLineNumbers` | string | 反编译工具 | 渲染可见行号：`decompiled`、`source`、`both`、`none` |
| `writeSidecarMetadata` | boolean | 反编译工具 | 在源文件旁写 .meta.json 辅助文件，默认 false |
| `preferences` | object | 部分反编译工具 | 传递给 transformer-api 的每引擎原始偏好设置 |

## 批量工具进度显示

`decompile_directory`、`save_all_sources`、`batch_decompile`、`batch_decompile_jars` 运行时会在 stderr 显示：

```
[jd-mcp-duo] decompile_directory: starting (4556 total)
[jd-mcp-duo] decompile_directory: 1/4556 (0%)
[jd-mcp-duo] decompile_directory -> lib/…/fine-app-core.jar > com.fr…ClassName
```

- 百分比行：0~10% 每 1% 一次，之后每 5%
- 箭头行：动态显示当前正在处理的文件（始终一行，原地刷新）

## 支持格式

`.class`、`.jar`、`.war`、`.ear`、`.kar`、`.zip`、`.jmod`、`.aar`、`.apk`、`.dex`

## SQLite 索引配置

索引默认路径为 `~/.jd-mcp-duo/index.sqlite`。推荐放项目目录下避免占满系统盘：

```bash
# 方式一：按工具指定 --indexPath
search_in_jar --path=./lib --query=MyClass --indexPath=./.jd-mcp-duo/index.sqlite

# 方式二：环境变量全局设置
JAVA_TOOL_OPTIONS="-Djd.mcp.sqlite.index=./.jd-mcp-duo/index.sqlite"
```

索引支持增量更新（基于内容指纹），只重新索引变更的 archive。手动删除 `.jd-mcp-duo/` 即清空。

## 输出路径规则

> **反编译输出必须保存在目标项目路径下，禁止写入工具目录。**

所有 `--output`、`--outputDir` 参数应指向项目审计输出目录，例如：

```bash
# 正确：输出到项目审计目录
--output=/target/project/audit/decompiled
--outputDir=/target/project/audit/src

# 错误：输出到工具目录
--output=D:/tools/tools/java/jd-mcp-duo/output
```

下面示例中 `{project}` 代表被审计项目的根路径，`{output}` 代表审计输出目录。

## MCP 模式注意事项

- **大响应控制**：批量工具设置 `verbose=false`（默认）仅返回计数，避免 AI 上下文溢出
- **存活检测**：调用 `help` 确认 MCP 连接正常
- **进度反馈**：长任务在 stderr 显示进度，在终端可见
- **栈大小**：处理大 archive 时建议 `-Xss10m`，CLI 参数错误会自动显示用法

## 安全审计典型用法

### 批量反编译

```bash
# 1. 反编译整个 WAR 保留目录结构（最常用）
D:/tools/tools/java/jd-mcp-duo/bin/jd-mcp-duo.bat save_all_sources --path={project}/app.war --output={output}/app-src

# 2. 反编译目录下所有 .class 和 archive，保留相对路径
D:/tools/tools/java/jd-mcp-duo/bin/jd-mcp-duo.bat decompile_directory --path={project}/WEB-INF --outputDir={output}/decompiled --recursive=true

# 3. 从目录根反编译指定多个类
D:/tools/tools/java/jd-mcp-duo/bin/jd-mcp-duo.bat batch_decompile --path={project}/classes --classes=com.example.Controller,com.example.Service

# 4. 跨多个 JAR 反编译指定类（递归扫描 + glob 过滤）
D:/tools/tools/java/jd-mcp-duo/bin/jd-mcp-duo.bat batch_decompile_jars --path={project}/libs --classes=com.example.Dao --recursive=true --pattern="*.jar"

# 5. 反编译并输出为 sources JAR
D:/tools/tools/java/jd-mcp-duo/bin/jd-mcp-duo.bat save_all_sources --path={project}/app.jar --output={output}/sources.jar --format=sources-jar

# 6. 分析 archive 概览并预览单个类
D:/tools/tools/java/jd-mcp-duo/bin/jd-mcp-duo.bat decompile_jar --path={project}/app.jar --decompile=true --className=com.example.Main --limit=20
```

### 搜索与分析

```bash
# 7. 搜索敏感字符串（密码、密钥等）— 支持通配符和正则
D:/tools/tools/java/jd-mcp-duo/bin/jd-mcp-duo.bat search_in_jar --path={project}/app.jar --query=password --type=string --queryMode=wildcard

# 8. 查找所有 Controller 类（通配符模式）
D:/tools/tools/java/jd-mcp-duo/bin/jd-mcp-duo.bat type_lookup --path={project}/app.jar --query="*Controller" --queryMode=wildcard

# 9. 查看类元数据（路由映射）
D:/tools/tools/java/jd-mcp-duo/bin/jd-mcp-duo.bat class_metadata --path={project}/app.jar --className=com.example.LoginController

# 10. 追踪调用链（从 Controller 到 DAO）
D:/tools/tools/java/jd-mcp-duo/bin/jd-mcp-duo.bat call_chain --path={project}/app.jar --className=com.example.Controller --methodName=upload --direction=callees --depth=5

# 11. 查找方法所有调用者
D:/tools/tools/java/jd-mcp-duo/bin/jd-mcp-duo.bat find_references --path={project}/app.jar --className=com.example.Util --kind=method --methodName=execute

# 12. 查看方法控制流图（Mermaid 格式）
D:/tools/tools/java/jd-mcp-duo/bin/jd-mcp-duo.bat show_cfg --path={project}/app.jar --className=com.example.Service --methodName=process --format=mermaid
```

### 依赖与诊断

```bash
# 13. 提取 Maven 依赖用于漏洞匹配
D:/tools/tools/java/jd-mcp-duo/bin/jd-mcp-duo.bat list_dependencies --path={project}/app.war --format=text --output={output}/deps.txt

# 14. 构建项目骨架推断依赖
D:/tools/tools/java/jd-mcp-duo/bin/jd-mcp-duo.bat build_skeleton --path={project}/libs --outputDir={output}/skeleton

# 15. 从 Maven Central 查找原始源码
D:/tools/tools/java/jd-mcp-duo/bin/jd-mcp-duo.bat source_lookup --path={project}/app.jar --className=com.example.Main --saveTo={output}/Main.java

# 16. 反编译质量报告
D:/tools/tools/java/jd-mcp-duo/bin/jd-mcp-duo.bat source_quality_report --path={project}/app.jar

# 17. 解析异常栈帧到源码行号
D:/tools/tools/java/jd-mcp-duo/bin/jd-mcp-duo.bat resolve_stacktrace --path={project}/app.jar --text="at com.example.Service.process(Service.java:42)"

# 18. 从日志文件解析栈帧（跨多个 archive）
D:/tools/tools/java/jd-mcp-duo/bin/jd-mcp-duo.bat resolve_stacktrace --path={project}/app.jar --textPath={project}/error.log --scopePath={project}/libs
```
