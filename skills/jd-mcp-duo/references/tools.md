# jd-mcp-duo Tools Reference

按需加载，补充 SKILL.md 中未覆盖的完整参数和 schema。

## 调用与元信息

| 工具 | 通道 | 功能 | 参数 |
|------|------|------|------|
| `index_scope` | CLI | 扫描 path 和 scopePath 下所有归档，建立或刷新 SQLite 跨归档索引。建索引有进度输出，后续查询秒回。 | `path*:string`, `scopePath:string`, `scopeRecursive:boolean=false`, `indexPath:string` |
| `help` | MCP/CLI | 列出可用工具和描述，可用于检查服务可用性。 | 无 |
| `list_engines` | MCP/CLI | 列出反编译引擎、别名和默认引擎。 | 无 |
| `describe_engine_options` | MCP/CLI | 查看指定引擎支持的选项和 raw preferences。 | `engine*:string` |

## 核心反编译

| 工具 | 推荐 | 功能 | 参数 |
|------|------|------|------|
| `decompile_class` | MCP | 从 `.class`、目录或归档中反编译单个类，返回源码和结构化元数据；可写到单个输出文件。 | `path*:string`, `className:string`, `engine:string`, `releaseVersion:integer`, `attemptTimeoutMillis:integer`, `lineNumbers:boolean`, `renderLineNumbers:string`, `writeSidecarMetadata:boolean=false`, `advancedLookup:boolean=false`, `classpath:string|array`, `preferences:object`, `output:string` |
| `decompile_advanced` | MCP | `decompile_class` 的增强入口，默认走 auto、依赖查找和 JD-Core v1/v0 方法级 patch。 | `path*:string`, `className:string`, `engine:string`, `releaseVersion:integer`, `attemptTimeoutMillis:integer`, `lineNumbers:boolean`, `renderLineNumbers:string`, `writeSidecarMetadata:boolean=false`, `advancedLookup:boolean=false`, `classpath:string|array`, `preferences:object`, `output:string` |
| `decompile_jar` | MCP | 分析归档或目录，并可用 `decompile=true` 预览指定类。 | `path*:string`, `className:string`, `limit:integer=20`, `decompile:boolean=false`, `engine:string`, `releaseVersion:integer=25`, `attemptTimeoutMillis:integer=30000`, `lineNumbers:boolean`, `renderLineNumbers:string`, `advancedLookup:boolean=false`, `classpath:string|array`, `preferences:object` |
| `save_all_sources` | CLI | 反编译输入归档或目录中的所有非内部类，保存为目录或 sources jar，并复制资源。 | `path*:string`, `output*:string`, `format:string`, `engine:string`, `releaseVersion:integer=25`, `attemptTimeoutMillis:integer=30000`, `lineNumbers:boolean`, `renderLineNumbers:string`, `writeSidecarMetadata:boolean=false`, `advancedLookup:boolean=false`, `classpath:string|array`, `verbose:boolean=false`, `preferences:object` |
| `decompile_directory` | CLI | 递归反编译目录下 `.class` 和支持归档，保持相对结构输出到目标目录，并复制资源。 | `path*:string`, `outputDir*:string`, `recursive:boolean=true`, `engine:string`, `releaseVersion:integer=25`, `attemptTimeoutMillis:integer=30000`, `lineNumbers:boolean`, `renderLineNumbers:string`, `writeSidecarMetadata:boolean=false`, `advancedLookup:boolean=false`, `classpath:string|array`, `summaryOnly:boolean=false`, `fileLimit:integer=0`, `verbose:boolean=false`, `preferences:object` |
| `batch_decompile` | CLI | 批量反编译目录或 class 根下的类；可输出目录或只返回摘要。 | `path*:string`, `engine:string`, `releaseVersion:integer=25`, `attemptTimeoutMillis:integer=30000`, `lineNumbers:boolean`, `renderLineNumbers:string`, `writeSidecarMetadata:boolean=false`, `advancedLookup:boolean=false`, `classpath:string|array`, `limit:integer=0`, `summaryOnly:boolean=false`, `verbose:boolean=false`, `outputDir:string`, `preferences:object` |
| `batch_decompile_jars` | CLI | 批量反编译目录中的支持归档；可限制归档数和每个归档类数。 | `path*:string`, `recursive:boolean=false`, `pattern:string`, `engine:string`, `releaseVersion:integer=25`, `attemptTimeoutMillis:integer=30000`, `lineNumbers:boolean`, `renderLineNumbers:string`, `writeSidecarMetadata:boolean=false`, `advancedLookup:boolean=false`, `classpath:string|array`, `classLimit:integer=0`, `jarLimit:integer=0`, `summaryOnly:boolean=false`, `verbose:boolean=false`, `outputDir:string`, `preferences:object` |

## 目录、类清单与元数据

| 工具 | 推荐 | 功能 | 参数 |
|------|------|------|------|
| `analyze_directory` | MCP | 分析目录下支持归档，汇总类数量、大小和文件分布。支持翻页和 `--output` 写文件。 | `path*:string`, `recursive:boolean=false`, `pattern:string`, `limit:integer=200`, `offset:integer=0`, `output:string` |
| `list_classes` | MCP | 列出归档或目录中的类名、内部名和包统计。支持翻页和 `--output` 写文件。 | `path*:string`, `package:string`, `releaseVersion:integer=25`, `includeInner:boolean=false`, `detailed:boolean=false`, `limit:integer=200`, `offset:integer=0`, `output:string` |
| `class_metadata` | MCP | 查看类级元数据、方法、字段、注解、访问标志和字节码版本。 | `path*:string`, `className:string`, `releaseVersion:integer=25` |
| `source_quality_report` | CLI | 抽样或全量反编译类，统计成功率、patch、fallback、失败原因和质量指标。 | `path*:string`, `engine:string`, `releaseVersion:integer=25`, `attemptTimeoutMillis:integer=30000`, `lineNumbers:boolean`, `advancedLookup:boolean=false`, `classpath:string|array`, `classLimit:integer=100`, `preferences:object` |

## 搜索、符号与调用关系

| 工具 | 推荐 | 功能 | 参数 |
|------|------|------|------|
| `search_in_jar` | CLI | 搜索类、构造器、方法、字段、字符串、模块和资源。MCP 下禁用建索引。支持 `--output` 全量写入文件。 | `path*:string`, `query*:string`, `type:string`, `queryMode:string`, `caseSensitive:boolean=false`, `scopePath:string`, `scopeRecursive:boolean=false`, `indexPath:string`, `distinct:boolean=false`, `limit:integer=50`, `offset:integer=0`, `output:string` |
| `type_lookup` | CLI | 按精确名、通配符或正则查找类型。MCP 下禁用建索引。支持 `--output` 写文件。 | `path*:string`, `query*:string`, `queryMode:string`, `scopePath:string`, `scopeRecursive:boolean=false`, `indexPath:string`, `caseSensitive:boolean=false`, `limit:integer=50`, `offset:integer=0`, `output:string` |
| `type_hierarchy` | CLI | 展示目标类父类、接口、子类和实现层次。MCP 下禁用建索引。 | `path*:string`, `className*:string`, `scopePath:string`, `scopeRecursive:boolean=false`, `indexPath:string`, `depth:integer=8`, `maxNodes:integer=256` |
| `find_references` | CLI | 查找类型、字段或方法引用。MCP 下禁用建索引。 | `path*:string`, `kind*:string`, `className*:string`, `fieldName:string`, `methodName:string`, `descriptor:string`, `scopePath:string`, `scopeRecursive:boolean=false`, `indexPath:string`, `depth:integer=1`, `maxNodes:integer=256` |
| `method_overrides` | CLI | 查找方法覆写和接口实现关系。MCP 下禁用建索引。 | `path*:string`, `className*:string`, `methodName*:string`, `descriptor:string`, `scopePath:string`, `scopeRecursive:boolean=false`, `indexPath:string`, `depth:integer=8`, `maxNodes:integer=256` |
| `resolve_symbol` | CLI | 解析类型、字段或方法符号到内部名、descriptor 和声明匹配。MCP 下禁用建索引。 | `path*:string`, `className:string`, `fieldName:string`, `methodName:string`, `descriptor:string`, `scopePath:string`, `scopeRecursive:boolean=false`, `indexPath:string` |
| `call_chain` | CLI | 构建静态 caller/callee 调用链；方向为 `callers`、`callees` 或 `both`。MCP 下禁用建索引。 | `path*:string`, `className*:string`, `methodName*:string`, `descriptor:string`, `direction:string`, `scopePath:string`, `scopeRecursive:boolean=false`, `indexPath:string`, `depth:integer=3`, `maxNodes:integer=128` |

## 日志、源码与依赖

| 工具 | 推荐 | 功能 | 参数 |
|------|------|------|------|
| `resolve_stacktrace` | CLI | 把 Java stacktrace 或日志帧解析到类、方法、候选归档和反编译行映射。MCP 下禁用建索引。 | `path*:string`, `text:string`, `textPath:string`, `scopePath:string`, `scopeRecursive:boolean=false`, `indexPath:string`, `engine:string`, `releaseVersion:integer=25`, `attemptTimeoutMillis:integer=30000`, `maxFrames:integer=200`, `lineMappingLimitPerFrame:integer=1`, `lineNumbers:boolean=false`, `advancedLookup:boolean=false`, `classpath:string|array`, `preferences:object` |
| `analyze_log` | CLI | `resolve_stacktrace` 的别名入口，用于日志分析。MCP 下禁用建索引。 | `path*:string`, `text:string`, `textPath:string`, `scopePath:string`, `scopeRecursive:boolean=false`, `indexPath:string`, `engine:string`, `releaseVersion:integer=25`, `attemptTimeoutMillis:integer=30000`, `maxFrames:integer=200`, `lineMappingLimitPerFrame:integer=1`, `lineNumbers:boolean=false`, `advancedLookup:boolean=false`, `classpath:string|array`, `preferences:object` |
| `source_lookup` | MCP | 从本地 sources jar、兄弟 `-sources.jar` 或 Maven 坐标查找原始源码。 | `path:string`, `className:string`, `sourceJarPath:string`, `groupId:string`, `artifactId:string`, `version:string`, `sha1:string`, `sha1File:string`, `configPath:string`, `searchProvider:string`, `searchBaseUrl:string`, `remoteContentBaseUrl:string`, `proxyHost:string`, `proxyPort:string`, `username:string`, `password:string`, `bearerToken:string`, `saveTo:string` |
| `list_dependencies` | MCP | 扫描 `META-INF/maven/**/pom.properties` 并列出嵌入 Maven 坐标。支持翻页。 | `path*:string`, `format:string`, `output:string`, `limit:integer=500`, `offset:integer=0` |
| `build_skeleton` | MCP | 从归档推断依赖坐标并生成 Maven/Gradle 构建骨架。 | `path*:string`, `files:string|array`, `outputDir:string`, `configPath:string`, `searchProvider:string`, `searchBaseUrl:string`, `remoteContentBaseUrl:string`, `proxyHost:string`, `proxyPort:string`, `username:string`, `password:string`, `bearerToken:string` |

## 字节码、CFG、比较与诊断

| 工具 | 推荐 | 功能 | 参数 |
|------|------|------|------|
| `show_bytecode` | MCP | 显示 `.class`、目录或归档中类的 javap 风格字节码。 | `path*:string`, `className:string`, `releaseVersion:integer=25`, `verbose:boolean=true` |
| `show_cfg` | MCP | 输出方法控制流图，包含 Mermaid 和结构化边。 | `path*:string`, `className:string`, `methodName*:string`, `descriptor:string`, `format:string`, `releaseVersion:integer=25` |
| `compare_jars` | MCP | 按条目大小和 CRC 比较两个归档，汇总新增、删除、修改类和资源。 | `jar1*:string`, `jar2*:string`, `detail:boolean=true` |
| `compare_class` | MCP | 比较两个类，或同一类在两个引擎配置下的反编译源码。 | `leftPath*:string`, `rightPath:string`, `leftClassName:string`, `rightClassName:string`, `className:string`, `leftEngine:string`, `rightEngine:string`, `releaseVersion:integer=25`, `attemptTimeoutMillis:integer=30000`, `lineNumbers:boolean`, `advancedLookup:boolean=false`, `classpath:string|array`, `preferences:object` |
| `compare_jd_core` | MCP | 比较同一类的 JD-Core v0 与 JD-Core v1 输出，是 `compare_class` 的便捷入口。 | `path*:string`, `className:string`, `rightPath:string`, `rightClassName:string`, `leftPath:string`, `leftClassName:string`, `releaseVersion:integer=25`, `attemptTimeoutMillis:integer=30000`, `lineNumbers:boolean`, `advancedLookup:boolean=false`, `classpath:string|array`, `preferences:object` |
| `compiler_diagnostics` | MCP | 使用 Eclipse JDT 分析 Java 源码或反编译输出，返回错误和警告。 | `path*:string`, `className:string`, `engine:string`, `releaseVersion:integer=25`, `attemptTimeoutMillis:integer=30000`, `lineNumbers:boolean`, `advancedLookup:boolean=false`, `classpath:string|array`, `preferences:object` |
| `remove_unnecessary_casts` | MCP | 使用 Eclipse JDT 分析并移除 Java 源码或反编译输出中的不必要强转。 | `path*:string`, `className:string`, `engine:string`, `releaseVersion:integer=25`, `attemptTimeoutMillis:integer=30000`, `lineNumbers:boolean`, `advancedLookup:boolean=false`, `classpath:string|array`, `saveTo:string`, `preferences:object` |

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

