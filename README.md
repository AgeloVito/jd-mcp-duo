[English](README.md) | [中文](README_zh.md)

# jd-mcp-duo

An MCP (Model Context Protocol) server and CLI toolkit for Java decompilation, powered by [transformer-api](https://github.com/nbauma109/transformer-api).

Built on the decompilation engine suite from [jd-gui-duo](https://github.com/nbauma109/jd-gui-duo), this project exposes the same multi-engine decompilation and static analysis capabilities through both MCP and CLI modes — MCP mode for AI agents, and CLI mode for direct terminal use or integration into scripts.

## Security Analysis Scenarios

jd-mcp-duo wraps decompilation, static call chains, cross-archive full-text search, and resource extraction into MCP tools, equipping security engineers' AI agents with the core capabilities needed for code auditing, dependency analysis, and vulnerability discovery — closed-source JARs, WARs, and APKs are no longer beyond reach.

Examples of security analysis scenarios this MCP service can support when connected to an AI agent:

- **Closed-source auditing** — The agent uses `save_all_sources` to decompile an entire JAR/WAR/APK while preserving the project directory structure, then reviews business logic class by class

  ```bash
  save_all_sources --path=target.war --output=./target-src
  ```

- **Taint tracking** — The agent leverages `call_chain`'s CHA-resolved static call graph, combined with its own knowledge of input sources and dangerous sinks, to trace data flow from external input to sensitive operations such as command execution

  ```bash
  call_chain --path=app.jar --className=com.example.Controller --methodName=upload --direction=callees --depth=5
  ```

- **Hardcoded secret discovery** — The agent uses `search_in_jar` to search for key string patterns across archives, locating hardcoded API keys, tokens, database connection strings, private keys, and other sensitive information

  ```bash
  search_in_jar --path=app.jar --query='password' --type=string
  ```

- **Dependency risk identification** — The agent uses `list_dependencies` to extract all embedded Maven coordinates from an archive, enabling cross-referencing with vulnerability intelligence to identify known-vulnerable dependencies

  ```bash
  list_dependencies --path=app.jar --format=text --output=deps.txt
  ```

- **Stack trace resolution** — The agent uses `resolve_stacktrace` to resolve each frame of an exception stack trace to a line-level position in the decompiled source, quickly pinpointing the exact code that triggered the exception

  ```bash
  resolve_stacktrace --path=app.jar --stacktrace=crash.log
  ```

## Architecture

```
jd-mcp-duo (this project)
├── MCP protocol layer — JSON-RPC 2.0 over stdio
├── CLI mode — command-line access without MCP
├── 31 tools — decompile, search, analyze, compare
├── SQLite index — cross-archive call graph and type hierarchy
└── archive abstraction — JAR/WAR/DEX/APK/directory input
```

Unlike the desktop [jd-gui-duo](https://github.com/nbauma109/jd-gui-duo) which has a Swing GUI and a multi-module SPI architecture, jd-mcp-duo is a single-module server optimized for programmatic access via MCP.

## Decompiler engines

All engines are accessed through `transformer-api`. The engine suite includes:

| Engine | Description |
|---|---|
| auto | Multi-engine fallback strategy (default) — tries JD-Core v1 first, falls back through v0 patching, Vineflower, CFR, Procyon, Fernflower, then JADX |
| JD-Core v1 | Analytical decompiler |
| JD-Core v0 | Pattern-matching decompiler (fallback + method patch source) |
| JD-Core Duo | v1 with v0 method patching on failure |
| CFR | Broadly compatible, stable output |
| Procyon | Readable output with line-number options |
| Fernflower | Classic analytical decompiler |
| Vineflower | Most accurate modern Java-focused decompiler |
| JADX | JVM + Android/DEX-oriented |

## Download

Each [release](https://github.com/AgeloVito/jd-mcp-duo/releases) includes:

| File | For |
|---|---|
| `jd-mcp-duo.jar` | Bare JAR — requires JDK 25+ |
| `jd-mcp-duo-macos-arm64.tar.xz` | macOS Apple Silicon (M1/M2/M3) |
| `jd-mcp-duo-macos-x64.tar.xz` | macOS Intel |
| `jd-mcp-duo-linux-x64.tar.xz` | Linux x64 |
| `jd-mcp-duo-windows-x64.zip` | Windows x64 |

Platform archives include a bundled JRE (jlink) — no Java installation required.

## Installation

Download the archive for your platform, extract, and run:

```bash
# macOS / Linux
# macOS / Linux (pick the right archive for your platform)
tar xf jd-mcp-duo-macos-arm64.tar.xz
./jd-mcp-duo/bin/jd-mcp-duo --help

# Windows
# Extract the zip, then:
jd-mcp-duo\bin\jd-mcp-duo.bat --help
```

## Usage

### MCP server mode

Configure your MCP client:

```json
{
  "mcpServers": {
    "jd-mcp-duo": {
      "command": "/path/to/jd-mcp-duo/bin/jd-mcp-duo"
    }
  }
}
```

The server communicates via JSON-RPC 2.0 over stdio. It supports MCP protocol versions 2025-11-25, 2025-06-18, 2025-03-26, and 2024-11-05.

### CLI mode

```bash
./bin/jd-mcp-duo <tool-name> [options]
```

## Tools

All tools can be invoked via CLI (`./bin/jd-mcp-duo <tool-name> [options]`) or through MCP tool calls.

### Core decompilation

**`decompile_class`** — Decompile a single class with structured metadata.

| Parameter | Required | Description |
|---|---|---|
| `path` | yes | Path to a .class file, archive, or directory |
| `className` | no | Class name when path is an archive or directory (e.g. `com.example.Main`) |

If `path` points to a single `.class` file, `className` is inferred automatically. For archives and directories, `className` selects which class to decompile.

```bash
./bin/jd-mcp-duo decompile_class --path=app.jar --className=com.example.Main
./bin/jd-mcp-duo decompile_class --path=com/example/Main.class
```

**`decompile_advanced`** — Decompile with classpath-assisted type resolution. Resolves dependencies from sibling archives and JDK modules for more accurate output.

| Parameter | Required | Description |
|---|---|---|
| `path` | yes | Input path |
| `className` | no | Class name when path is an archive or directory |
| `advancedLookup` | no | Enable sibling archive and JDK module dependency resolution (default: false) |
| `classpath` | no | Additional classpath entries |

```bash
./bin/jd-mcp-duo decompile_advanced --path=app.jar --className=com.example.Main --advancedLookup=true
```

**`decompile_jar`** — Decompile all classes in an archive and output a summary with per-class engine statistics.

| Parameter | Required | Description |
|---|---|---|
| `path` | yes | Path to a supported archive |

```bash
./bin/jd-mcp-duo decompile_jar --path=app.jar --engine=vineflower
```

### Batch processing

**`save_all_sources`** — Decompile every class in an archive or directory and write the results to a directory or sources JAR. Non-class resources (XML, properties, images, templates, etc.) are preserved alongside decompiled sources. Output structure mirrors the input archive structure.

| Parameter | Required | Description |
|---|---|---|
| `path` | yes | Input archive or directory |
| `output` | yes | Output directory or sources JAR path |
| `format` | no | `directory` (default) or `sources-jar` |
| `engine` | no | Decompiler engine (default: auto) |

```bash
./bin/jd-mcp-duo save_all_sources --path=app.jar --output=./app-src
./bin/jd-mcp-duo save_all_sources --path=app.jar --output=sources.jar --format=sources-jar
```

**`decompile_directory`** — Recursively scan a directory for `.class` files and supported archives, decompile them into a target directory while preserving relative paths. Non-class files are copied as-is.

| Parameter | Required | Description |
|---|---|---|
| `path` | yes | Input directory |
| `outputDir` | yes | Output directory |
| `recursive` | no | Recursively scan subdirectories (default: true) |

```bash
./bin/jd-mcp-duo decompile_directory --path=./input --outputDir=./output --engine=jadx
```

**`analyze_directory`** — Scan a directory for supported archives and report class counts and sizes without decompiling.

| Parameter | Required | Description |
|---|---|---|
| `path` | yes | Directory to analyze |
| `recursive` | no | Recursively scan subdirectories (default: true) |

```bash
./bin/jd-mcp-duo analyze_directory --path=./libs
```

**`batch_decompile`** — Decompile multiple specific classes from a directory root.

| Parameter | Required | Description |
|---|---|---|
| `path` | yes | Directory root containing class files |
| `classes` | yes | Comma-separated list of class names to decompile |

```bash
./bin/jd-mcp-duo batch_decompile --path=./classes --classes=com.a.Foo,com.b.Bar
```

**`batch_decompile_jars`** — Decompile specific classes across multiple archives in a directory.

| Parameter | Required | Description |
|---|---|---|
| `path` | yes | Directory containing archives |
| `classes` | yes | Comma-separated list of class names to decompile |

```bash
./bin/jd-mcp-duo batch_decompile_jars --path=./libs --classes=com.a.Foo,com.b.Bar
```

### Inspection

**`list_classes`** — List all classes in an archive or directory with normalized names and package statistics.

| Parameter | Required | Description |
|---|---|---|
| `path` | yes | Path to an archive or directory |

```bash
./bin/jd-mcp-duo list_classes --path=app.jar
```

**`class_metadata`** — Inspect class-level metadata: access flags, bytecode version, superclass, interfaces, module name, methods, fields, annotations, and constant pool statistics.

| Parameter | Required | Description |
|---|---|---|
| `path` | yes | Input path |
| `className` | no | Class name when path is an archive or directory |

```bash
./bin/jd-mcp-duo class_metadata --path=app.jar --className=com.example.Main
```

**`list_engines`** — List all available decompiler engines, their aliases, and supported profiles (fast/accurate/debuggable).

```bash
./bin/jd-mcp-duo list_engines
```

**`describe_engine_options`** — Describe the configurable options and supported profiles for a specific decompiler engine.

| Parameter | Required | Description |
|---|---|---|
| `engine` | yes | Engine name (e.g. `cfr`, `jadx`, `fernflower`) |

```bash
./bin/jd-mcp-duo describe_engine_options --engine=cfr
```

### Search and analysis

**`search_in_jar`** — Index-based search across classes, methods, constructors, fields, string constants, and resource files. Supports `*` and `?` wildcards. Results include archive paths for scoped searches.

| Parameter | Required | Description |
|---|---|---|
| `path` | yes | Primary input path |
| `query` | yes | Search term with wildcard support |
| `type` | no | Filter: `any`, `type`, `constructor`, `method`, `field`, `string`, `module`, `service`, `manifest`, `xml`, `properties`, `json`, `yaml`, `text`, `config` |
| `scopePath` | no | Multi-archive scope path |

```bash
./bin/jd-mcp-duo search_in_jar --path=app.jar --query=getUser* --type=method
```

**`type_lookup`** — Look up type declarations by exact name, wildcard, or regex across the current input and optional scope.

| Parameter | Required | Description |
|---|---|---|
| `path` | yes | Primary input path |
| `className` | yes | Type name (exact, `*pattern`, or `/regex/`) |
| `scopePath` | no | Multi-archive scope path |

```bash
./bin/jd-mcp-duo type_lookup --path=app.jar --className='*Service' --scopePath=./libs
```

**`type_hierarchy`** — Build supertype and subtype hierarchy trees for a class. Searches across all scoped archives using the SQLite index.

| Parameter | Required | Description |
|---|---|---|
| `path` | yes | Primary input path |
| `className` | yes | Class name |
| `scopePath` | no | Multi-archive scope path |

```bash
./bin/jd-mcp-duo type_hierarchy --path=app.jar --className=com.example.BaseService --scopePath=./libs
```

**`find_references`** — Find all references to a type, field, or method across the indexed scope. Resolves method-owner, field-owner, and type-usage references from bytecode instructions.

| Parameter | Required | Description |
|---|---|---|
| `path` | yes | Primary input path |
| `className` | yes | Target class name |
| `kind` | no | `type`, `method`, or `field` |
| `memberName` | no | Method or field name (for method/field references) |
| `descriptor` | no | JVM descriptor for overload resolution |
| `scopePath` | no | Multi-archive scope path |

```bash
./bin/jd-mcp-duo find_references --path=app.jar --className=com.example.Util --kind=method --memberName=format
```

**`method_overrides`** — Find override and implementation relationships for a method. Shows which methods override the target and which methods the target overrides.

| Parameter | Required | Description |
|---|---|---|
| `path` | yes | Primary input path |
| `className` | yes | Declaring class name |
| `methodName` | yes | Method name |
| `descriptor` | no | JVM descriptor for overload resolution |
| `scopePath` | no | Multi-archive scope path |

```bash
./bin/jd-mcp-duo method_overrides --path=app.jar --className=com.example.BaseDao --methodName=save
```

**`resolve_symbol`** — Resolve a type, field, or method symbol to internal names, JVM descriptors, and matching declarations. Handles overloaded methods by listing all candidate descriptors.

| Parameter | Required | Description |
|---|---|---|
| `path` | yes | Primary input path |
| `className` | yes | Declaring class name |
| `symbolName` | yes | Symbol name (type, field, or method) |
| `kind` | no | `type`, `field`, or `method` (default: method) |
| `scopePath` | no | Multi-archive scope path |

```bash
./bin/jd-mcp-duo resolve_symbol --path=app.jar --className=com.example.Service --symbolName=process --kind=method
```

**`resolve_stacktrace`** (alias: `analyze_log`) — Parse Java stack trace or log text, resolve each frame to its declaring class, method, descriptor, candidate source archives, and decompiled line number mappings.

| Parameter | Required | Description |
|---|---|---|
| `path` | yes | Primary input path |
| `stacktrace` | yes | Stack trace or log text |
| `scopePath` | no | Multi-archive scope path |

```bash
./bin/jd-mcp-duo resolve_stacktrace --path=app.jar --stacktrace=stacktrace.log
./bin/jd-mcp-duo analyze_log --path=app.jar --stacktrace=stacktrace.log
```

**`source_lookup`** — Look up original source code from a local sources JAR, sibling `-sources.jar`, or Maven Central using SHA-1 coordinates.

| Parameter | Required | Description |
|---|---|---|
| `path` | yes | Primary input path |
| `className` | no | Class name to look up |
| `methodName` | no | Method name for method-level lookup |
| `lineNumber` | no | Line number for position-level lookup |

```bash
./bin/jd-mcp-duo source_lookup --path=app.jar --className=com.example.Main --lineNumber=42
```

**`call_chain`** — Build a static caller/callee chain with BFS traversal. Uses Class Hierarchy Analysis (CHA) to resolve `invokevirtual`/`invokeinterface` calls to concrete implementations within the indexed scope.

| Parameter | Required | Description |
|---|---|---|
| `path` | yes | Path to a supported archive or directory |
| `className` | yes | Declaring class name |
| `methodName` | yes | Method name |
| `descriptor` | no | JVM descriptor for overload resolution |
| `direction` | no | `callers`, `callees`, or `both` (default: both) |
| `depth` | no | Recursion depth (default: 3) |
| `maxNodes` | no | Maximum nodes returned (default: 128) |
| `scopePath` | no | Multi-archive scope path |

```bash
./bin/jd-mcp-duo call_chain --path=app.jar --className=com.example.Service --methodName=handle --direction=callees --depth=5
```

**`source_quality_report`** — Report decompilation quality metrics across all classes in an archive: success rate, engine used, patch count, fallback rate, and warnings.

| Parameter | Required | Description |
|---|---|---|
| `path` | yes | Input archive or directory |
| `engine` | no | Decompiler engine to evaluate (default: auto) |

```bash
./bin/jd-mcp-duo source_quality_report --path=app.jar
```

### Bytecode and control flow

**`show_bytecode`** — Display javap-style bytecode for a method from a `.class` file, directory, or archive.

| Parameter | Required | Description |
|---|---|---|
| `path` | yes | Input path |
| `className` | yes | Class name |
| `methodName` | yes | Method name |
| `descriptor` | no | JVM descriptor for overload resolution |

```bash
./bin/jd-mcp-duo show_bytecode --path=app.jar --className=com.example.Main --methodName=main --descriptor='([Ljava/lang/String;)V'
```

**`show_cfg`** — Generate a method control flow graph as Mermaid markup and structured edge data.

| Parameter | Required | Description |
|---|---|---|
| `path` | yes | Input path |
| `className` | yes | Class name |
| `methodName` | yes | Method name |
| `descriptor` | no | JVM descriptor for overload resolution |

```bash
./bin/jd-mcp-duo show_cfg --path=app.jar --className=com.example.Main --methodName=process
```

### Comparison

**`compare_jars`** — Compare two archives by entry size and CRC-32, reporting added, removed, and modified entries. Handles nested JARs inside WAR/EAR.

| Parameter | Required | Description |
|---|---|---|
| `jar1` | yes | First archive path |
| `jar2` | yes | Second archive path |

```bash
./bin/jd-mcp-duo compare_jars --jar1=v1/app.jar --jar2=v2/app.jar
```

**`compare_class`** — Compare decompiled source for the same class under two different engine configurations, or the same class across two different input paths.

| Parameter | Required | Description |
|---|---|---|
| `leftPath` | yes | Left-side input path |
| `rightPath` | no | Right-side path (defaults to leftPath) |
| `className` | yes | Class name |
| `leftEngine` | no | Engine for left side (default: auto) |
| `rightEngine` | no | Engine for right side (default: auto) |

```bash
./bin/jd-mcp-duo compare_class --leftPath=app.jar --className=com.example.Main --leftEngine=cfr --rightEngine=vineflower
```

**`compare_jd_core`** — Side-by-side comparison of JD-Core v0 and JD-Core v1 decompiled output for the same class. Useful for investigating decompilation differences and understanding why method patching was necessary.

| Parameter | Required | Description |
|---|---|---|
| `path` | yes | Input path |
| `className` | yes | Class name |

```bash
./bin/jd-mcp-duo compare_jd_core --path=app.jar --className=com.example.Complex
```

### Code generation and diagnostics

**`build_skeleton`** — Generate a Maven/Gradle build skeleton from one or more archives. Resolves dependency coordinates (groupId:artifactId:version) from META-INF/maven metadata, SHA-1 lookup on Maven Central, and manifest attributes.

| Parameter | Required | Description |
|---|---|---|
| `path` | yes | Primary archive or directory |
| `scopePath` | no | Additional archives for dependency inference |
| `outputDir` | no | Directory to write generated pom.xml, build.gradle, and mvn_deploy.bat |

```bash
./bin/jd-mcp-duo build_skeleton --path=./libs --outputDir=./skeleton
```

**`list_dependencies`** — Scan an archive and list all embedded Maven dependencies found under META-INF/maven/. Outputs as JSON or GAV text format, optionally writing to a file.

| Parameter | Required | Description |
|---|---|---|
| `path` | yes | Path to a supported archive |
| `format` | no | `json` (default) or `text` (GAV per line) |
| `output` | no | File path to write the dependency list |

```bash
./bin/jd-mcp-duo list_dependencies --path=app.jar --format=text --output=deps.txt
```

**`compiler_diagnostics`** — Run the Eclipse JDT compiler on a Java source file or decompiled output, reporting errors, warnings, and info markers. Useful for validating decompiled output correctness.

| Parameter | Required | Description |
|---|---|---|
| `path` | yes | Path to a .java source file |

```bash
./bin/jd-mcp-duo compiler_diagnostics --path=./src/com/example/Main.java
```

**`remove_unnecessary_casts`** — Remove unnecessary type casts from Java source or decompiled output using Eclipse JDT's `CleanUpRefactoring`. Reports which casts were removed and the cleaned source.

| Parameter | Required | Description |
|---|---|---|
| `path` | yes | Path to a .java source file |

```bash
./bin/jd-mcp-duo remove_unnecessary_casts --path=./src/com/example/Main.java
```

### Common parameters

| Parameter | Applicable tools | Description |
|---|---|---|
| `engine` | decompilation tools | Decompiler engine: `auto`, `jd-core-v1`, `jd-core-v0`, `jd-core-duo`, `cfr`, `procyon`, `fernflower`, `vineflower`, `jadx` |
| `profile` | decompilation tools | `fast`, `accurate`, or `debuggable` — selects appropriate engine and options |
| `scopePath` | search/analysis tools | Directory or archive for multi-archive scope indexing |
| `releaseVersion` | decompilation tools | Target multi-release class version (defaults to current Java version) |
| `descriptor` | method-level tools | JVM method descriptor for overload disambiguation, e.g. `(Ljava/lang/String;)V` |

## Supported formats

- `.class` — Java class files
- `.jar` / `.war` / `.ear` / `.kar` — Java archives (including Spring Boot fat JARs)
- `.zip` — Generic ZIP archives
- `.jmod` — Java modules
- `.aar` — Android archives
- `.apk` / `.dex` — Android packages and DEX files

## Key features

### Multi-engine fallback

When the preferred engine fails (StackOverflowError, empty output, decompilation markers), the auto profile tries fallback engines in a configured order. Per-engine timeouts prevent hung decompilation.

### JD-Core v1 + v0 method patching

When JD-Core v1 produces output with decompilation failures, individual failed methods are automatically patched from JD-Core v0 output using Eclipse JDT AST merging.

### SQLite cross-archive index

A persistent SQLite index stores pre-computed class, method, field, string, type reference, call graph, and resource data across all scoped archives. This enables fast search, call chain analysis, and type hierarchy resolution without re-scanning bytecode.

The index is incrementally updated via content fingerprinting — only changed archives are re-indexed.

### Resource preservation

When decompiling archives to directories, non-class resources (XML configs, properties, images, MANIFEST.MF, templates, etc.) are preserved alongside the decompiled `.java` files, producing a complete project skeleton.

### Static call chain with CHA

The `call_chain` tool performs static call graph analysis using Class Hierarchy Analysis (CHA) to resolve virtual method invocations (`invokevirtual`, `invokeinterface`) to concrete implementations within the indexed scope, and uses BFS traversal for balanced graph exploration under node limits.

## Configuration

The SQLite index path can be overridden via the `JAVA_TOOL_OPTIONS` environment variable, or by editing the launcher script:

```bash
JAVA_TOOL_OPTIONS="-Djd.mcp.sqlite.index=/path/to/custom/index.sqlite" ./bin/jd-mcp-duo <tool-name> [options]
```

## Credits

This project builds on the decompilation capabilities from [jd-gui-duo](https://github.com/nbauma109/jd-gui-duo) and shares the same core dependency, [transformer-api](https://github.com/nbauma109/transformer-api).

| Component | Author | Link | License |
|---|---|---|---|
| transformer-api | nbauma109 | https://github.com/nbauma109/transformer-api | MIT |
| CFR | Lee Benfield | https://github.com/leibnitz27/cfr | MIT |
| Procyon | Mike Strobel | https://github.com/mstrobel/procyon | Apache v2 |
| Fernflower | JetBrains | https://github.com/JetBrains/intellij-community | Apache v2 |
| Vineflower | Vineflower | https://github.com/Vineflower/vineflower | Apache v2 |
| JADX | Skylot | https://github.com/skylot/jadx | Apache v2 |
| JD-GUI | Emmanuel Dupuy | https://github.com/java-decompiler/jd-gui | GPL v3 |

## License

MIT
