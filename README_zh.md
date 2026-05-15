[English](README.md) | [中文](README_zh.md)

# jd-mcp-duo

基于 [transformer-api](https://github.com/nbauma109/transformer-api) 的 MCP (Model Context Protocol) 服务和 CLI 工具集，用于 Java 反编译。

本项目复用 [jd-gui-duo](https://github.com/nbauma109/jd-gui-duo) 的反编译引擎套件，将相同的多引擎反编译和静态分析能力通过 MCP 服务和 CLI 两种方式暴露——MCP 模式下可直接被 AI Agent 调用，CLI 模式适合人工在终端使用或集成到自动化脚本中。

## 典型安全分析场景

jd-mcp-duo 将反编译、静态调用链、跨归档全文搜索和资源提取封装为 MCP 工具，为安全工程师的 AI Agent 提供代码审计、依赖分析和漏洞发现所需的核心能力——闭源 JAR/WAR/APK 不再不可审计。

接入 AI Agent 后，使用该 MCP 服务可支撑的一些典型安全分析场景：

- **闭源审计** — Agent 通过 `save_all_sources` 反编译整个 JAR/WAR/APK 并保留项目目录结构，对闭源应用逐类审查业务逻辑

  ```bash
  save_all_sources --path=target.war --output=./target-src
  ```

- **污点追踪** — Agent 以 `call_chain` 的 CHA 静态调用图为基础，配合自身对输入源和危险函数的识别，追踪从外部输入到命令执行等危险操作的数据流路径

  ```bash
  call_chain --path=app.jar --className=com.example.Controller --methodName=upload --direction=callees --depth=5
  ```

- **硬编码密钥发现** — Agent 通过 `search_in_jar` 在归档中以关键字符串模式检索，批量定位硬编码的 API Key、Token、数据库连接串、私钥等敏感信息

  ```bash
  search_in_jar --path=app.jar --query='password' --type=string
  ```

- **依赖风险定位** — Agent 通过 `list_dependencies` 提取归档中所有嵌入式 Maven 坐标，以便结合漏洞情报识别其中的已知漏洞依赖

  ```bash
  list_dependencies --path=app.jar --format=text --output=deps.txt
  ```

- **堆栈回溯定位** — Agent 通过 `resolve_stacktrace` 将异常堆栈日志中的每一帧解析到反编译源码的行级位置，快速定位触发异常的具体代码

  ```bash
  resolve_stacktrace --path=app.jar --stacktrace=crash.log
  ```

## 架构

```
jd-mcp-duo（本项目）
├── MCP 协议层 — JSON-RPC 2.0 over stdio
├── CLI 模式 — 无需 MCP 的命令行调用
├── 33 个工具 — 反编译、搜索、分析、对比
├── SQLite 索引 — 跨归档调用图和类型层次
└── 归档抽象 — JAR/WAR/DEX/APK/目录输入
```

与桌面版 [jd-gui-duo](https://github.com/nbauma109/jd-gui-duo)（Swing GUI + 多模块 SPI 架构）不同，jd-mcp-duo 是单模块无界面服务，为程序化 MCP 调用优化。

## 反编译引擎

所有引擎通过 `transformer-api` 统一调用，包括：

| 引擎 | 说明 |
|---|---|
| auto | 多引擎 fallback 策略（默认）— 优先 JD-Core v1，依次回退 v0 修补、Vineflower、CFR、Procyon、Fernflower、JADX |
| JD-Core v1 | 分析型反编译器 |
| JD-Core v0 | 模式匹配型反编译器（fallback + 方法修补源） |
| JD-Core Duo | v1 输出中失败的方法自动用 v0 修补 |
| CFR | 兼容性广，输出稳定 |
| Procyon | 可读性好，支持行号选项 |
| Fernflower | 经典分析型反编译器 |
| Vineflower | 最准确的现代 Java 反编译器 |
| JADX | JVM + Android/DEX 优化 |

## 下载

每个 [Release](https://github.com/AgeloVito/jd-mcp-duo/releases) 包含：

| 文件 | 适用平台 |
|---|---|
| `jd-mcp-duo.jar` | 纯 JAR — 需 JDK 25+ |
| `jd-mcp-duo-macos-arm64.tar.xz` | macOS Apple Silicon（M1/M2/M3） |
| `jd-mcp-duo-macos-x64.tar.xz` | macOS Intel |
| `jd-mcp-duo-linux-x64.tar.xz` | Linux x64 |
| `jd-mcp-duo-windows-x64.zip` | Windows x64 |

平台包内嵌 jlink 裁剪的 JRE，无需安装 Java。

## 安装

下载对应平台的归档文件，解压即用：

```bash
# macOS / Linux
tar xf jd-mcp-duo-macos-arm64.tar.xz
./jd-mcp-duo/bin/jd-mcp-duo --help

# Windows
# 解压 zip 后：
jd-mcp-duo\bin\jd-mcp-duo.bat --help
```

## 使用

### MCP 服务模式

在 MCP 客户端中配置。以下方式任选其一：

```bash
# 方式一：claude mcp add 命令（推荐）
claude mcp add --transport stdio --scope user jd-mcp-duo -- /path/to/jd-mcp-duo/bin/jd-mcp-duo

# 验证是否添加成功
claude mcp list
claude mcp get jd-mcp-duo
```

```json
// 方式二：直接编辑 ~/.claude.json
{
  "mcpServers": {
    "jd-mcp-duo": {
      "type": "stdio",
      "command": "/path/to/jd-mcp-duo/bin/jd-mcp-duo",
      "args": [],
      "env": {}
    }
  }
}
```

服务通过 JSON-RPC 2.0 over stdio 通信，支持 MCP 协议版本 2025-11-25、2025-06-18、2025-03-26、2024-11-05。

### CLI 模式

```bash
./bin/jd-mcp-duo <工具名> [参数]
```

## 工具列表

所有工具均可通过 CLI 调用（`./bin/jd-mcp-duo <工具名> [参数]`）或通过 MCP 工具调用。

### 核心反编译

**`decompile_class`** — 反编译单个类，返回结构化元数据。

| 参数 | 必填 | 说明 |
|---|---|---|
| `path` | 是 | .class 文件、归档或目录的路径 |
| `className` | 否 | 当 path 指向归档或目录时指定类名（如 `com.example.Main`） |
| `engine` | 否 | 反编译引擎（默认：auto） |
| `profile` | 否 | `fast`、`accurate` 或 `debuggable` |

```bash
./bin/jd-mcp-duo decompile_class --path=app.jar --className=com.example.Main
./bin/jd-mcp-duo decompile_class --path=com/example/Main.class
```

**`decompile_advanced`** — 带 classpath 辅助类型解析的反编译。从同级归档和 JDK 模块中解析依赖，生成更准确的输出。

| 参数 | 必填 | 说明 |
|---|---|---|
| `path` | 是 | 输入路径 |
| `className` | 否 | 当 path 指向归档或目录时指定类名 |
| `advancedLookup` | 否 | 启用同级归档和 JDK 模块依赖解析（默认：false） |
| `classpath` | 否 | 额外的 classpath 条目 |

```bash
./bin/jd-mcp-duo decompile_advanced --path=app.jar --className=com.example.Main --advancedLookup=true
```

**`decompile_jar`** — 反编译归档中的所有类，输出摘要及每个类使用的引擎统计。

```bash
./bin/jd-mcp-duo decompile_jar --path=app.jar --engine=vineflower
```

### 批量处理

**`save_all_sources`** — 将归档或目录中的每个类反编译并写入目录或源码 JAR。非 class 资源文件（XML、properties、图片、模板等）随反编译源码一同保留。输出结构镜像输入归档结构。

| 参数 | 必填 | 说明 |
|---|---|---|
| `path` | 是 | 输入归档或目录 |
| `output` | 是 | 输出目录或源码 JAR 路径 |
| `format` | 否 | `directory`（默认）或 `sources-jar` |
| `engine` | 否 | 反编译引擎（默认：auto） |

```bash
./bin/jd-mcp-duo save_all_sources --path=app.jar --output=./app-src
./bin/jd-mcp-duo save_all_sources --path=app.jar --output=sources.jar --format=sources-jar
```

**`decompile_directory`** — 递归扫描目录中的 `.class` 文件和归档，反编译到目标目录并保留相对路径。非 class 文件原样复制。

```bash
./bin/jd-mcp-duo decompile_directory --path=./input --outputDir=./output --engine=jadx
```

**`analyze_directory`** — 扫描目录中的归档文件，报告类数量和大小，不进行反编译。

```bash
./bin/jd-mcp-duo analyze_directory --path=./libs
```

**`batch_decompile`** — 从目录根路径批量反编译指定的多个类。

```bash
./bin/jd-mcp-duo batch_decompile --path=./classes --classes=com.a.Foo,com.b.Bar
```

**`batch_decompile_jars`** — 从目录中的多个归档批量反编译指定类。

```bash
./bin/jd-mcp-duo batch_decompile_jars --path=./libs --classes=com.a.Foo,com.b.Bar
```

### 查看

**`list_classes`** — 列出归档或目录中的所有类，包含规范化的类名和包统计信息。

```bash
./bin/jd-mcp-duo list_classes --path=app.jar
```

**`class_metadata`** — 查看类级别元数据：访问标志、字节码版本、父类、接口、模块名、方法、字段、注解和常量池统计。

```bash
./bin/jd-mcp-duo class_metadata --path=app.jar --className=com.example.Main
```

**`list_engines`** — 列出所有可用的反编译引擎、别名和支持的 profile。

```bash
./bin/jd-mcp-duo list_engines
```

**`describe_engine_options`** — 描述指定反编译引擎的可配置选项和支持的 profile。

```bash
./bin/jd-mcp-duo describe_engine_options --engine=cfr
```

### 搜索与分析

**`search_in_jar`** — 基于索引搜索类、方法、字段、字符串常量和资源文件。支持 `*` 和 `?` 通配符。

| 参数 | 必填 | 说明 |
|---|---|---|
| `path` | 是 | 主输入路径 |
| `query` | 是 | 搜索词（支持通配符） |
| `type` | 否 | 过滤类型：`any`、`type`、`constructor`、`method`、`field`、`string`、`module` 等 |
| `scopePath` | 否 | 多归档 scope 路径 |

```bash
./bin/jd-mcp-duo search_in_jar --path=app.jar --query=getUser* --type=method
```

**`type_lookup`** — 跨当前输入和可选 scope 按精确名称、通配符或正则查找类型声明。

```bash
./bin/jd-mcp-duo type_lookup --path=app.jar --className='*Service' --scopePath=./libs
```

**`type_hierarchy`** — 构建类的父类和子类层次树。使用 SQLite 索引跨所有 scope 归档搜索。

```bash
./bin/jd-mcp-duo type_hierarchy --path=app.jar --className=com.example.BaseService --scopePath=./libs
```

**`find_references`** — 在索引范围内查找类型、字段或方法的所有引用。从字节码指令中解析方法引用、字段引用和类型使用。

```bash
./bin/jd-mcp-duo find_references --path=app.jar --className=com.example.Util --kind=method --memberName=format
```

**`method_overrides`** — 查找方法的重写和实现关系。展示哪些方法重写了目标方法，以及目标方法重写了哪些方法。

```bash
./bin/jd-mcp-duo method_overrides --path=app.jar --className=com.example.BaseDao --methodName=save
```

**`resolve_symbol`** — 将类型、字段或方法符号解析为内部名称、JVM 描述符和匹配的声明。对重载方法列出所有候选项。

```bash
./bin/jd-mcp-duo resolve_symbol --path=app.jar --className=com.example.Service --symbolName=process --kind=method
```

**`resolve_stacktrace`**（别名：`analyze_log`）— 解析 Java 堆栈或日志文本，将每一帧解析到声明类、方法、描述符、候选源归档和反编译行号映射。

```bash
./bin/jd-mcp-duo resolve_stacktrace --path=app.jar --stacktrace=stacktrace.log
./bin/jd-mcp-duo analyze_log --path=app.jar --stacktrace=stacktrace.log
```

**`source_lookup`** — 从本地源码 JAR、同级 `-sources.jar` 或通过 SHA-1 坐标从 Maven Central 查找原始源码。

```bash
./bin/jd-mcp-duo source_lookup --path=app.jar --className=com.example.Main --lineNumber=42
```

**`call_chain`** — 构建静态调用链（BFS 遍历）。使用类层次分析（CHA）将 `invokevirtual`/`invokeinterface` 调用解析到索引范围内的具体实现。

| 参数 | 必填 | 说明 |
|---|---|---|
| `path` | 是 | 输入路径 |
| `className` | 是 | 声明类名 |
| `methodName` | 是 | 方法名 |
| `descriptor` | 否 | JVM 描述符（用于重载消歧） |
| `direction` | 否 | `callers`、`callees` 或 `both`（默认：both） |
| `depth` | 否 | 递归深度（默认：3） |
| `maxNodes` | 否 | 最大返回节点数（默认：128） |
| `scopePath` | 否 | 多归档 scope 路径 |

```bash
./bin/jd-mcp-duo call_chain --path=app.jar --className=com.example.Service --methodName=handle --direction=callees --depth=5
```

**`source_quality_report`** — 报告归档中所有类的反编译质量指标：成功率、使用的引擎、修补数量、回退率和警告。

```bash
./bin/jd-mcp-duo source_quality_report --path=app.jar
```

### 字节码与控制流

**`show_bytecode`** — 显示方法的 javap 风格字节码。

```bash
./bin/jd-mcp-duo show_bytecode --path=app.jar --className=com.example.Main --methodName=main --descriptor='([Ljava/lang/String;)V'
```

**`show_cfg`** — 生成方法的控制流图（Mermaid 格式及结构化边数据）。

```bash
./bin/jd-mcp-duo show_cfg --path=app.jar --className=com.example.Main --methodName=process
```

### 对比

**`compare_jars`** — 按条目大小和 CRC-32 对比两个归档，报告新增、移除和修改的条目。

```bash
./bin/jd-mcp-duo compare_jars --jar1=v1/app.jar --jar2=v2/app.jar
```

**`compare_class`** — 对比同一类在不同引擎配置或不同输入路径下的反编译源码。

```bash
./bin/jd-mcp-duo compare_class --leftPath=app.jar --className=com.example.Main --leftEngine=cfr --rightEngine=vineflower
```

**`compare_jd_core`** — 并排对比 JD-Core v0 和 JD-Core v1 对同一类的反编译输出。用于研究反编译差异和理解方法修补的原因。

```bash
./bin/jd-mcp-duo compare_jd_core --path=app.jar --className=com.example.Complex
```

### 代码生成与诊断

**`build_skeleton`** — 从一个或多个归档生成 Maven/Gradle 构建骨架。从 META-INF/maven 元数据、SHA-1 查 Maven Central 以及 manifest 属性中解析依赖坐标。

| 参数 | 必填 | 说明 |
|---|---|---|
| `path` | 是 | 主归档或目录 |
| `scopePath` | 否 | 用于依赖推断的额外归档 |
| `outputDir` | 否 | 输出目录，写入 pom.xml、build.gradle 和 mvn_deploy.bat |

```bash
./bin/jd-mcp-duo build_skeleton --path=./libs --outputDir=./skeleton
```

**`list_dependencies`** — 扫描归档，列出 META-INF/maven/ 下所有嵌入式 Maven 依赖。支持 JSON 或纯文本 GAV 格式输出，可选写入文件。

| 参数 | 必填 | 说明 |
|---|---|---|
| `path` | 是 | 归档路径 |
| `format` | 否 | `json`（默认）或 `text`（每行一个 GAV） |
| `output` | 否 | 将依赖清单写入指定文件 |

```bash
./bin/jd-mcp-duo list_dependencies --path=app.jar --format=text --output=deps.txt
```

**`compiler_diagnostics`** — 对 Java 源码文件或反编译输出运行 Eclipse JDT 编译器，报告错误、警告和信息标记。

```bash
./bin/jd-mcp-duo compiler_diagnostics --path=./src/com/example/Main.java
```

**`remove_unnecessary_casts`** — 使用 Eclipse JDT 的 `CleanUpRefactoring` 移除 Java 源码或反编译输出中不必要的类型转换。

```bash
./bin/jd-mcp-duo remove_unnecessary_casts --path=./src/com/example/Main.java
```


### 元工具

**`help`** — MCP 存活检测。列出所有可用工具及描述。MCP 模式下可通过此工具确认服务是否正常运行。

| 参数 | 必填 | 说明 |
|---|---|---|
| *(无)* | — | 无参数；直接返回工具列表 |

```bash
./bin/jd-mcp-duo help
```
### 公共参数

| 参数 | 适用工具 | 说明 |
|---|---|---|
| `engine` | 反编译类工具 | 引擎名：`auto`、`jd-core-v1`、`jd-core-v0`、`jd-core-duo`、`cfr`、`procyon`、`fernflower`、`vineflower`、`jadx` |
| `profile` | 反编译类工具 | `fast`、`accurate` 或 `debuggable` — 自动选择合适的引擎和选项 |
| `indexPath` | 搜索/分析类工具 | 自定义 SQLite 索引路径（默认：`~/.jd-mcp-duo/index.sqlite`） |
| `verbose` | 批量反编译工具 | 结构化结果中包含每个文件的详细信息（默认：false） |
| `scopePath` | 搜索/分析类工具 | 用于多归档 scope 索引的目录或归档 |
| `releaseVersion` | 反编译类工具 | 目标多版本 class 版本号（默认：当前 Java 版本） |
| `descriptor` | 方法级工具 | JVM 方法描述符，用于重载消歧，如 `(Ljava/lang/String;)V` |

## 支持格式

- `.class` — Java 类文件
- `.jar` / `.war` / `.ear` / `.kar` — Java 归档（含 Spring Boot fat JAR）
- `.zip` — 通用 ZIP 归档
- `.jmod` — Java 模块
- `.aar` — Android 归档
- `.apk` / `.dex` — Android 包和 DEX 文件

## 核心特性

### 多引擎 fallback

当首选引擎失败（StackOverflowError、空输出、反编译失败标记）时，auto 策略按配置顺序尝试 fallback 引擎。每引擎可设超时防止卡死。

### JD-Core v1 + v0 方法修补

当 JD-Core v1 输出包含反编译失败的方法时，自动从 JD-Core v0 输出中提取对应方法，通过 Eclipse JDT AST 合并修补。

### SQLite 跨归档索引

持久化 SQLite 索引存储所有 scope 归档的类、方法、字段、字符串、类型引用、调用图和资源数据，支持快速搜索、调用链分析和类型层次解析，无需重复扫描字节码。

索引通过内容指纹增量更新——仅重新索引变更的归档。

### 资源文件保留

反编译归档到目录时，非 class 资源文件（XML 配置、properties、图片、MANIFEST.MF、模板等）会随反编译的 `.java` 文件一同保留，生成完整的项目骨架。

### 静态调用链 + CHA

`call_chain` 工具使用类层次分析（CHA）将虚方法调用（`invokevirtual`、`invokeinterface`）解析到索引范围内的具体实现，并使用 BFS 遍历在节点配额下均衡展示调用图。

## 配置

SQLite 索引默认路径为 `~/.jd-mcp-duo/index.sqlite`。可通过 `--indexPath` 参数按工具指定：

```bash
./bin/jd-mcp-duo search_in_jar --path=./lib --query=MyClass --indexPath=/d/projects/my-index.sqlite
```

或全局通过 `JAVA_TOOL_OPTIONS` 环境变量：

```bash
JAVA_TOOL_OPTIONS="-Djd.mcp.sqlite.index=/path/to/custom/index.sqlite" ./bin/jd-mcp-duo <工具名> [参数]
```

### 栈大小

处理大型归档时建议增大线程栈：

```bash
java -Xss10m -jar jd-mcp-duo.jar <工具名> [参数]
```

## 致谢

本项目基于 [jd-gui-duo](https://github.com/nbauma109/jd-gui-duo) 的反编译能力构建，共享核心依赖 [transformer-api](https://github.com/nbauma109/transformer-api)。

| 组件 | 作者 | 链接 | 许可证 |
|---|---|---|---|
| transformer-api | nbauma109 | https://github.com/nbauma109/transformer-api | MIT |
| CFR | Lee Benfield | https://github.com/leibnitz27/cfr | MIT |
| Procyon | Mike Strobel | https://github.com/mstrobel/procyon | Apache v2 |
| Fernflower | JetBrains | https://github.com/JetBrains/intellij-community | Apache v2 |
| Vineflower | Vineflower | https://github.com/Vineflower/vineflower | Apache v2 |
| JADX | Skylot | https://github.com/skylot/jadx | Apache v2 |
| JD-GUI | Emmanuel Dupuy | https://github.com/java-decompiler/jd-gui | GPL v3 |

## 许可证

MIT
