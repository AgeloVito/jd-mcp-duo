# jd-mcp-duo Tools Reference

这个文件是 `SKILL.md` 的详细工具索引。普通任务优先看主 Skill 的“场景化工作流”和“任务到工具映射”；只有需要精确参数、排查 schema、或用户要求列全量功能时加载本文件。

## 代码事实

- 当前 `MCPServer.registerTools()` 注册 34 个工具；同一批工具也全部可通过 CLI 子命令调用。
- CLI 不带参数启动本地 stdio MCP；带 `<tool>` 子命令进入 CLI 模式。
- CLI `--json` 输出 `{text, structuredData, isError}`；不带 `--json` 只输出文本摘要。
- MCP `tools/call` 返回 `content` 文本、可选 `structuredContent`、`isError`。工具执行失败通常也是 `tools/call` result 的 `isError=true`，不是 JSON-RPC error。
- 参数支持 `--key=value`、`--key value`、重复 key 数组、无值布尔 true；值会解析为 JSON object/array、boolean、integer、decimal 或 string。
- `path` 类参数会做安全校验：空值、控制字符、任何 `..` 路径段都会被拒绝。

## 支持输入和布局

- 输入类型：目录、单 `.class`、`.jar`、`.war`、`.zip`、`.jmod`、`.aar`、`.ear`、`.kar`、`.apk`、`.dex`。
- 归档类根：普通根、`BOOT-INF/classes/`、`WEB-INF/classes/`、`classes/`、`jmod/classes/`。
- 多版本类：`META-INF/versions/<n>/`，由 `releaseVersion` 选择不高于目标版本的最高条目。
- 嵌套依赖：`BOOT-INF/lib/`、`WEB-INF/lib/`、`APP-INF/lib/`、`lib/`、`libs/`、`dependencies/` 和根级归档。
- EAR/KAR 嵌套模块会暴露为 primary classes；AAR 用 `classes.jar`；APK/DEX 会转换为 class map 并优先 native JADX。

## 引擎、Profile 和输出元数据

- 引擎：`auto`、`jd-core-duo`、`jd-core-v1`、`jd-core-v0`、`cfr`、`procyon`、`fernflower`、`vineflower`、`jadx`。
- 常用别名：`jd-duo`/`duo`、`jd-core`/`jd`、`v0`、`ff`、`vf`。
- `auto`：APK/DEX 先 native JADX；普通 class 先 JD-Core v1，失败标记时用 JD-Core v0 方法级 patch，再按 profile fallback。
- `jd-core-duo`：只使用 JD-Core v1/v0 和 patch，不切到 CFR/Vineflower/JADX。
- Profile：`fast`、`accurate`、`debuggable`。`debuggable` 在未显式传 `lineNumbers` 时默认开启行号元数据。
- `lineNumbers=true` 只返回 line mapping 元数据；要把行号写进源码文本，用 `renderLineNumbers=decompiled|source|both`。
- 常见结构化反编译字段：`engineRequested`、`engineUsed`、`patched`、`fallbackUsed`、`metadataLimited`、`metadataRebuilt`、`nativeAndroid`、`methodPatches`、`attemptedEngines`、`engineFailures`、`warnings`、`source`、`declarations`、`references`、`hyperlinks`、`lineNumbers`。

## Scope 和索引

- `scopePath` 为空时 scope 等于主 `path`；跨归档分析必须显式传 `scopePath`。
- `scopeRecursive=true` 用于递归收集目录下的归档；目录中有 class 文件时目录本身也会作为输入。
- SQLite 索引默认在 `~/.jd-mcp-duo/index.sqlite`；项目隔离建议传 `indexPath=./.jd-mcp-duo/index.sqlite`。
- **建索引用 `index_scope`**：先建索引再查询，避免阻塞。MCP 模式下所有工具禁止建索引（ThreadLocal 守卫），仅 CLI `index_scope` 可建。
- 索引基于文件内容 SHA-256 指纹增量更新；已索引的归档跳过不重扫。
- 索引会跳过损坏输入并记录 partial failures；如果没有任何输入成功索引，工具才整体失败。
- 调用链和引用图会保留 `sourcePath`，避免多归档同名方法边混淆。

## 通道选择

| 推荐通道 | 适用工具 |
|----------|----------|
| CLI | `index_scope`、`save_all_sources`、`decompile_directory`、`batch_decompile`、`batch_decompile_jars`、`source_quality_report`，以及 8 个索引查询工具（`search_in_jar`、`type_lookup`、`type_hierarchy`、`find_references`、`method_overrides`、`call_chain`、`resolve_symbol`、`resolve_stacktrace`）。 |
| MCP | `decompile_class`、`decompile_advanced`、`decompile_jar`、`list_classes`、`class_metadata`、`list_dependencies`、`source_lookup`、`compare_jars`、`compare_class`、`compare_jd_core`、`show_bytecode`、`show_cfg`、`build_skeleton`、`compiler_diagnostics`、`remove_unnecessary_casts`。 |
| 任意 | `help`、`list_engines`、`describe_engine_options`、`analyze_directory`。 |

## 调用与元信息

| 工具 | 推荐 | 功能 | 参数 |
|------|------|------|------|
| `index_scope` | CLI | 扫描 path 和 scopePath 下所有归档，建立或刷新 SQLite 跨归档索引。建索引有进度输出，后续查询秒回。 | `path*:string`, `scopePath:string`, `scopeRecursive:boolean=false`, `indexPath:string` |
| `help` | MCP/CLI | 列出可用工具和描述，可用于检查服务可用性。 | 无 |
| `list_engines` | MCP/CLI | 列出反编译引擎、别名、profile 和默认引擎。 | 无 |
| `describe_engine_options` | MCP/CLI | 查看指定引擎支持的选项、默认 profile 和 raw preferences。 | `engine*:string` |

## 核心反编译

| 工具 | 推荐 | 功能 | 参数 |
|------|------|------|------|
| `decompile_class` | MCP | 从 `.class`、目录或归档中反编译单个类，返回源码和结构化元数据；可写到单个输出文件。 | `path*:string`, `className:string`, `engine:string`, `profile:string`, `releaseVersion:integer`, `attemptTimeoutMillis:integer`, `lineNumbers:boolean`, `renderLineNumbers:string`, `writeSidecarMetadata:boolean=false`, `advancedLookup:boolean=false`, `classpath:string|array`, `preferences:object`, `output:string` |
| `decompile_advanced` | MCP | `decompile_class` 的增强入口，默认走 auto、依赖查找和 JD-Core v1/v0 方法级 patch。 | `path*:string`, `className:string`, `engine:string`, `profile:string`, `releaseVersion:integer`, `attemptTimeoutMillis:integer`, `lineNumbers:boolean`, `renderLineNumbers:string`, `writeSidecarMetadata:boolean=false`, `advancedLookup:boolean=false`, `classpath:string|array`, `preferences:object`, `output:string` |
| `decompile_jar` | MCP | 分析归档或目录，并可用 `decompile=true` 预览指定类。 | `path*:string`, `className:string`, `limit:integer=20`, `decompile:boolean=false`, `engine:string`, `profile:string`, `releaseVersion:integer`, `attemptTimeoutMillis:integer=30000`, `lineNumbers:boolean`, `renderLineNumbers:string`, `advancedLookup:boolean=false`, `classpath:string|array`, `preferences:object` |
| `save_all_sources` | CLI | 反编译输入归档或目录中的所有非内部类，保存为目录或 sources jar，并复制资源。 | `path*:string`, `output*:string`, `format:string`, `engine:string`, `profile:string`, `releaseVersion:integer`, `attemptTimeoutMillis:integer=30000`, `lineNumbers:boolean`, `renderLineNumbers:string`, `writeSidecarMetadata:boolean=false`, `advancedLookup:boolean=false`, `classpath:string|array`, `verbose:boolean=false` |
| `decompile_directory` | CLI | 递归反编译目录下 `.class` 和支持归档，保持相对结构输出到目标目录，并复制资源。 | `path*:string`, `outputDir*:string`, `recursive:boolean=true`, `engine:string`, `profile:string`, `releaseVersion:integer`, `attemptTimeoutMillis:integer=30000`, `lineNumbers:boolean`, `renderLineNumbers:string`, `writeSidecarMetadata:boolean=false`, `advancedLookup:boolean=false`, `classpath:string|array`, `summaryOnly:boolean=false`, `fileLimit:integer=0`, `verbose:boolean=false` |
| `batch_decompile` | CLI | 批量反编译目录或 class 根下的类；可输出目录或只返回摘要。 | `path*:string`, `engine:string`, `profile:string`, `releaseVersion:integer`, `attemptTimeoutMillis:integer=30000`, `lineNumbers:boolean`, `renderLineNumbers:string`, `writeSidecarMetadata:boolean=false`, `advancedLookup:boolean=false`, `classpath:string|array`, `limit:integer=0`, `summaryOnly:boolean=false`, `verbose:boolean=false`, `outputDir:string`, `preferences:object` |
| `batch_decompile_jars` | CLI | 批量反编译目录中的支持归档；可限制归档数和每个归档类数。 | `path*:string`, `recursive:boolean=false`, `pattern:string`, `engine:string`, `profile:string`, `releaseVersion:integer`, `attemptTimeoutMillis:integer=30000`, `lineNumbers:boolean`, `renderLineNumbers:string`, `writeSidecarMetadata:boolean=false`, `advancedLookup:boolean=false`, `classpath:string|array`, `classLimit:integer=0`, `jarLimit:integer=0`, `summaryOnly:boolean=false`, `verbose:boolean=false`, `outputDir:string`, `preferences:object` |

## 目录、类清单与元数据

| 工具 | 推荐 | 功能 | 参数 |
|------|------|------|------|
| `analyze_directory` | MCP | 分析目录下支持归档，汇总类数量、大小和文件分布。 | `path*:string`, `recursive:boolean=false`, `pattern:string`, `limit:integer=200`, `offset:integer=0` |
| `list_classes` | MCP | 列出归档或目录中的类名、内部名和包统计。 | `path*:string`, `package:string`, `releaseVersion:integer`, `includeInner:boolean=false`, `detailed:boolean=false`, `limit:integer=200`, `offset:integer=0` |
| `class_metadata` | MCP | 查看类级元数据、方法、字段、注解、访问标志和字节码版本。 | `path*:string`, `className:string`, `releaseVersion:integer` |
| `source_quality_report` | CLI | 抽样或全量反编译类，统计成功率、patch、fallback、失败原因和质量指标。 | `path*:string`, `engine:string`, `profile:string`, `releaseVersion:integer`, `attemptTimeoutMillis:integer=30000`, `lineNumbers:boolean`, `advancedLookup:boolean=false`, `classpath:string|array`, `classLimit:integer=100` |

## 搜索、符号与调用关系

| 工具 | 推荐 | 功能 | 参数 |
|------|------|------|------|
| `search_in_jar` | CLI | 搜索类、构造器、方法、字段、字符串、模块和资源。MCP 下禁用建索引。 | `path*:string`, `query*:string`, `type:string`, `queryMode:string`, `caseSensitive:boolean=false`, `scopePath:string`, `scopeRecursive:boolean=false`, `indexPath:string`, `distinct:boolean=false`, `limit:integer=50`, `offset:integer=0` |
| `type_lookup` | CLI | 按精确名、通配符或正则查找类型。MCP 下禁用建索引。 | `path*:string`, `query*:string`, `queryMode:string`, `scopePath:string`, `scopeRecursive:boolean=false`, `indexPath:string`, `caseSensitive:boolean=false`, `limit:integer=50`, `offset:integer=0` |
| `type_hierarchy` | CLI | 展示目标类父类、接口、子类和实现层次。MCP 下禁用建索引。 | `path*:string`, `className*:string`, `scopePath:string`, `scopeRecursive:boolean=false`, `indexPath:string`, `depth:integer=8`, `maxNodes:integer=256` |
| `find_references` | CLI | 查找类型、字段或方法引用。MCP 下禁用建索引。 | `path*:string`, `kind*:string`, `className*:string`, `fieldName:string`, `methodName:string`, `descriptor:string`, `scopePath:string`, `scopeRecursive:boolean=false`, `indexPath:string`, `depth:integer=1`, `maxNodes:integer=256` |
| `method_overrides` | CLI | 查找方法覆写和接口实现关系。MCP 下禁用建索引。 | `path*:string`, `className*:string`, `methodName*:string`, `descriptor:string`, `scopePath:string`, `scopeRecursive:boolean=false`, `indexPath:string`, `depth:integer=8`, `maxNodes:integer=256` |
| `resolve_symbol` | CLI | 解析类型、字段或方法符号到内部名、descriptor 和声明匹配。MCP 下禁用建索引。 | `path*:string`, `className:string`, `fieldName:string`, `methodName:string`, `descriptor:string`, `scopePath:string`, `scopeRecursive:boolean=false`, `indexPath:string` |
| `call_chain` | CLI | 构建静态 caller/callee 调用链；方向为 `callers`、`callees` 或 `both`。MCP 下禁用建索引。 | `path*:string`, `className*:string`, `methodName*:string`, `descriptor:string`, `direction:string`, `scopePath:string`, `scopeRecursive:boolean=false`, `indexPath:string`, `depth:integer=3`, `maxNodes:integer=128` |

## 日志、源码与依赖

| 工具 | 推荐 | 功能 | 参数 |
|------|------|------|------|
| `resolve_stacktrace` | CLI | 把 Java stacktrace 或日志帧解析到类、方法、候选归档和反编译行映射。MCP 下禁用建索引。 | `path*:string`, `text:string`, `textPath:string`, `scopePath:string`, `scopeRecursive:boolean=false`, `indexPath:string`, `engine:string`, `attemptTimeoutMillis:integer=30000`, `maxFrames:integer=200`, `lineMappingLimitPerFrame:integer=1` |
| `analyze_log` | CLI | `resolve_stacktrace` 的别名入口，用于日志分析。MCP 下禁用建索引。 | `path*:string`, `text:string`, `textPath:string`, `scopePath:string`, `scopeRecursive:boolean=false`, `indexPath:string`, `engine:string`, `attemptTimeoutMillis:integer=30000`, `maxFrames:integer=200`, `lineMappingLimitPerFrame:integer=1` |
| `source_lookup` | MCP | 从本地 sources jar、兄弟 `-sources.jar` 或 Maven 坐标查找原始源码。 | `path:string`, `className:string`, `sourceJarPath:string`, `groupId:string`, `artifactId:string`, `version:string`, `sha1:string`, `sha1File:string`, `configPath:string`, `searchProvider:string`, `searchBaseUrl:string`, `remoteContentBaseUrl:string`, `proxyHost:string`, `proxyPort:string`, `username:string`, `password:string`, `bearerToken:string`, `saveTo:string` |
| `list_dependencies` | MCP | 扫描 `META-INF/maven/**/pom.properties` 并列出嵌入 Maven 坐标。 | `path*:string`, `format:string`, `output:string`, `limit:integer=500`, `offset:integer=0` |
| `build_skeleton` | MCP | 从归档推断依赖坐标并生成 Maven/Gradle 构建骨架。 | `path*:string`, `files:string|array`, `outputDir:string`, `configPath:string`, `searchProvider:string`, `searchBaseUrl:string`, `remoteContentBaseUrl:string`, `proxyHost:string`, `proxyPort:string`, `username:string`, `password:string`, `bearerToken:string` |

## 字节码、CFG、比较与诊断

| 工具 | 推荐 | 功能 | 参数 |
|------|------|------|------|
| `show_bytecode` | MCP | 显示 `.class`、目录或归档中类的 javap 风格字节码。 | `path*:string`, `className:string`, `releaseVersion:integer`, `verbose:boolean=true` |
| `show_cfg` | MCP | 输出方法控制流图，包含 Mermaid 和结构化边。 | `path*:string`, `className:string`, `methodName*:string`, `descriptor:string`, `format:string`, `releaseVersion:integer` |
| `compare_jars` | MCP | 按条目大小和 CRC 比较两个归档，汇总新增、删除、修改类和资源。 | `jar1*:string`, `jar2*:string`, `detail:boolean=true` |
| `compare_class` | MCP | 比较两个类，或同一类在两个引擎配置下的反编译源码。 | `leftPath*:string`, `rightPath:string`, `leftClassName:string`, `rightClassName:string`, `leftEngine:string`, `rightEngine:string`, `releaseVersion:integer`, `attemptTimeoutMillis:integer=30000`, `lineNumbers:boolean`, `advancedLookup:boolean=false`, `classpath:string|array` |
| `compare_jd_core` | MCP | 比较同一类的 JD-Core v0 与 JD-Core v1 输出，是 `compare_class` 的便捷入口。 | `path*:string`, `className:string`, `rightPath:string`, `rightClassName:string`, `releaseVersion:integer`, `attemptTimeoutMillis:integer=30000`, `lineNumbers:boolean`, `advancedLookup:boolean=false`, `classpath:string|array` |
| `compiler_diagnostics` | MCP | 使用 Eclipse JDT 分析 Java 源码或反编译输出，返回错误和警告。 | `path*:string`, `className:string`, `engine:string`, `profile:string`, `attemptTimeoutMillis:integer=30000`, `lineNumbers:boolean`, `advancedLookup:boolean=false`, `classpath:string|array` |
| `remove_unnecessary_casts` | MCP | 使用 Eclipse JDT 分析并移除 Java 源码或反编译输出中的不必要强转。 | `path*:string`, `className:string`, `engine:string`, `profile:string`, `attemptTimeoutMillis:integer=30000`, `lineNumbers:boolean`, `advancedLookup:boolean=false`, `classpath:string|array`, `saveTo:string` |

## 参数坑位

- `className` 可用点分名或内部名；单 `.class` 且只有一个类时可以省略。
- `descriptor` 是 JVM 方法描述符，只描述参数和返回值；不要把方法名写进 descriptor。
- `queryMode=plain` 是默认安全选择；只有用户写 `*` / `?` 才用 `wildcard`，明确正则才用 `regex`。
- `limit`、`classLimit`、`jarLimit`、`fileLimit` 在批量工具里为 `0` 通常表示不限；不要默认传 0。
- `renderLineNumbers=none` 和不传类似；`decompiled`、`source`、`both` 会改变返回或写入的源码文本。
- `writeSidecarMetadata=true` 只在写文件时有意义，会生成 `.java.meta.json` 或 sources jar 内的 `.meta.json` entry。
- `advancedLookup=true` 会查找 sibling archives 和额外 classpath，准确性更好但会增加 IO。
- `preferences` 是原始 transformer-api/JD-Core/CFR/Vineflower/JADX 参数；JD-Core v0-only preferences 对 JD-Core v1 会被忽略并产生 warning。
- 写文件工具会创建目录并可能覆盖文件；代理层必须先确认目标目录策略。
- `source_lookup` 没有 schema 必填项，但实际调用至少应提供 `path`/`className`、`sourceJarPath` 或 Maven 坐标/sha1。

