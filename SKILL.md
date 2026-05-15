---
name: jd-mcp-duo
description: Java multi-engine decompiler MCP server and CLI toolkit. Supports JD-Core v0/v1, CFR, Procyon, Fernflower, Vineflower, JADX engines, providing 33 tools covering decompilation, search, call chain analysis, type hierarchy, bytecode/CFG inspection, and more. Use for: (1) decompiling JAR/WAR/APK without source code, (2) static call chain tracing (CHA analysis), (3) cross-archive full-text search and indexing, (4) dependency extraction and version identification, (5) stack trace resolution, (6) CFG control flow graph generation. **Bundled JRE — no system Java required**.
---

# jd-mcp-duo — Java Multi-Engine Decompiler Toolkit

## Quick Liveness Check

In MCP mode, call `help` to verify the server is alive:

```
help
→ jd-mcp-duo mcp server is alive. 33 tools available: decompile_class, ...
```

## Usage

This Skill calls jd-mcp-duo tools via MCP protocol — **no need to worry about installation paths**. Just describe what you need:

> /jd-mcp-duo decompile app.jar for me

### CLI Mode (optional)

```bash
jd-mcp-duo <tool-name> [options]
java -Xss10m -jar jd-mcp-duo.jar <tool-name> [options]
```

Parameter format: `--key=value` or `--key value`. Add `--json` for structured JSON output.

### MCP Setup

```bash
claude mcp add --transport stdio --scope user jd-mcp-duo -- /path/to/jd-mcp-duo/bin/jd-mcp-duo
claude mcp list && claude mcp get jd-mcp-duo  # verify
```

v4.2.5.2, bundled JRE 25, protocol 2025-11-25+.

## Tool List (33 tools)

### Meta

| Tool | Purpose | Parameters |
|------|---------|------------|
| `help` | MCP liveness check, lists all tools with descriptions | None |

### Core Decompilation

| Tool | Purpose | Required | Key Optional |
|------|---------|----------|-------------|
| `decompile_class` | Decompile a single class with structured metadata | `path` | `className`, `engine`, `profile`, `releaseVersion`, `attemptTimeoutMillis`, `lineNumbers`, `renderLineNumbers`, `writeSidecarMetadata`, `advancedLookup`, `classpath`, `preferences`, `output` |
| `decompile_advanced` | Auto engine selection + JD-Core v1/v0 method-level patching | `path` | Inherits all `decompile_class` params |
| `decompile_jar` | Analyze an archive and optionally preview one decompiled class | `path` | `className`, `limit`(default 20), `decompile`(bool), `engine`, `profile`, `releaseVersion`, `attemptTimeoutMillis`, `lineNumbers`, `renderLineNumbers`, `advancedLookup`, `classpath`, `preferences` |
| `save_all_sources` | Decompile entire archive to directory or sources JAR (**preserves directory structure**) | `path`, `output` | `format`(directory/sources-jar), `engine`, `profile`, `releaseVersion`, `attemptTimeoutMillis`, `lineNumbers`, `renderLineNumbers`, `writeSidecarMetadata`, `advancedLookup`, `classpath`, `verbose`(default false) |
| `decompile_directory` | Recursively scan a directory and decompile (preserves relative paths, shows inner-class progress for archives) | `path`, `outputDir` | `recursive`(default true), `engine`, `profile`, `releaseVersion`, `attemptTimeoutMillis`, `lineNumbers`, `renderLineNumbers`, `writeSidecarMetadata`, `advancedLookup`, `classpath`, `summaryOnly`, `fileLimit`, `verbose`(default false) |
| `batch_decompile` | Decompile specific classes from a directory root | `path` | `engine`, `profile`, `releaseVersion`, `attemptTimeoutMillis`, `lineNumbers`, `renderLineNumbers`, `writeSidecarMetadata`, `advancedLookup`, `classpath`, `limit`, `summaryOnly`, `verbose`(default false), `outputDir`, `preferences` |
| `batch_decompile_jars` | Decompile specific classes across multiple archives | `path` | `recursive`, `pattern`, `engine`, `profile`, `releaseVersion`, `attemptTimeoutMillis`, `lineNumbers`, `renderLineNumbers`, `writeSidecarMetadata`, `advancedLookup`, `classpath`, `classLimit`, `jarLimit`, `summaryOnly`, `verbose`(default false), `outputDir`, `preferences` |

### Inspection & Metadata

| Tool | Purpose | Required | Key Optional |
|------|---------|----------|-------------|
| `list_classes` | List classes in an archive with package statistics | `path` | `package`(prefix filter), `releaseVersion`, `includeInner`(bool), `detailed`(bool), `limit`(default 200) |
| `class_metadata` | Inspect class-level metadata (flags, bytecode version, methods, fields, annotations) | `path` | `className`, `releaseVersion` |
| `list_engines` | List all available decompiler engines, aliases, and profiles | None | — |
| `describe_engine_options` | Describe configurable options for a specific engine | `engine` | — |
| `analyze_directory` | Scan directory for archives, report class counts and sizes | `path` | `recursive`(bool), `pattern`(glob), `limit`(default 200) |
| `source_quality_report` | Decompilation quality metrics report | `path` | `engine`, `profile`, `releaseVersion`, `attemptTimeoutMillis`, `lineNumbers`, `advancedLookup`, `classpath`, `classLimit`(default 100, 0=unlimited) |

### Search & Analysis

| Tool | Purpose | Required | Key Optional |
|------|---------|----------|-------------|
| `search_in_jar` | Index-based search across classes, methods, fields, strings, and resources | `path`, `query` | `type`(type/class/constructor/method/field/string/module/resource/xml/properties/service/manifest/yaml/json/all), `queryMode`(plain/wildcard/regex), `caseSensitive`(bool), `scopePath`, `scopeRecursive`(bool), `distinct`(bool, string dedup), `indexPath`, `limit`(default 50) |
| `type_lookup` | Find type declarations by name, wildcard, or regex | `path`, `query` | `queryMode`(plain/wildcard/regex), `caseSensitive`(bool), `scopePath`, `scopeRecursive`(bool), `indexPath`, `limit`(default 50) |
| `type_hierarchy` | Build supertype/subtype hierarchy trees | `path`, `className` | `scopePath`, `scopeRecursive`(bool), `indexPath`, `depth`(default 8), `maxNodes`(default 256) |
| `find_references` | Find all references to a type, field, or method (includes targetDescriptor) | `path`, `kind`, `className` | `fieldName`, `methodName`, `descriptor`, `scopePath`, `scopeRecursive`(bool), `indexPath`, `depth`(default 1), `maxNodes`(default 256) |
| `method_overrides` | Find method override/implementation relationships | `path`, `className`, `methodName` | `descriptor`, `scopePath`, `scopeRecursive`(bool), `indexPath`, `depth`(default 8), `maxNodes`(default 256) |
| `resolve_symbol` | Resolve type/field/method symbols to JVM descriptors | `path` | `className`, `fieldName`, `methodName`, `descriptor`, `scopePath`, `scopeRecursive`(bool), `indexPath` |
| `resolve_stacktrace` | Resolve Java stacktrace frames to decompiled source line numbers | `path` | `text`(raw stacktrace), `textPath`(.log file), `scopePath`, `scopeRecursive`(bool), `indexPath`, `engine`, `attemptTimeoutMillis`, `maxFrames`(default 200), `lineMappingLimitPerFrame`(default 1) |
| `analyze_log` | Alias for resolve_stacktrace | Same as resolve_stacktrace | Same as resolve_stacktrace |
| `source_lookup` | Look up original source from sources JAR or Maven Central | — | `path`, `className`, `sourceJarPath`, `groupId`, `artifactId`, `version`, `sha1`, `sha1File`, `searchProvider`, `searchBaseUrl`, `remoteContentBaseUrl`, `proxyHost`, `proxyPort`, `username`, `password`, `bearerToken`, `saveTo` |
| `call_chain` | **Static call chain tracing** (CHA analysis, BFS traversal) | `path`, `className`, `methodName` | `descriptor`, `direction`(callers/callees/both, default both), `scopePath`, `scopeRecursive`(bool), `indexPath`, `depth`(default 3), `maxNodes`(default 128) |

### Bytecode & Control Flow

| Tool | Purpose | Required | Key Optional |
|------|---------|----------|-------------|
| `show_bytecode` | Display javap-style bytecode | `path` | `className`, `releaseVersion`, `verbose`(bool, default true, controls javap -v) |
| `show_cfg` | Generate method control flow graph (Mermaid/PlantUML) | `path`, `methodName` | `className`, `descriptor`, `format`(mermaid/plantuml/both, default mermaid), `releaseVersion` |

### Comparison

| Tool | Purpose | Required | Key Optional |
|------|---------|----------|-------------|
| `compare_jars` | Compare two archives (added/removed/modified entries) | `jar1`, `jar2` | `detail`(bool, default true) |
| `compare_class` | Compare same class under different engines or paths | `leftPath`, `className` | `rightPath`(default same as leftPath), `leftClassName`, `rightClassName`, `leftEngine`, `rightEngine`, `releaseVersion`, `attemptTimeoutMillis`, `lineNumbers`, `advancedLookup`, `classpath` |
| `compare_jd_core` | Side-by-side JD-Core v0 vs v1 comparison | `path`, `className` | `rightPath`, `rightClassName`, `releaseVersion`, `attemptTimeoutMillis`, `lineNumbers`, `advancedLookup`, `classpath` |

### Code Generation & Diagnostics

| Tool | Purpose | Required | Key Optional |
|------|---------|----------|-------------|
| `build_skeleton` | Generate Maven/Gradle build skeleton from archives | `path` | `files`, `outputDir`, `configPath`, `searchProvider`(maven-central/nexus2/nexus3), `searchBaseUrl`, `remoteContentBaseUrl`, `proxyHost`, `proxyPort`, `username`, `password`, `bearerToken` |
| `list_dependencies` | Scan archive for embedded Maven dependency coordinates | `path` | `format`(json/text), `output`(file path), `limit`(default 500) |
| `compiler_diagnostics` | Eclipse JDT compiler diagnostics validation | `path` | `className`, `engine`, `profile`, `attemptTimeoutMillis`, `lineNumbers`, `advancedLookup`, `classpath` |
| `remove_unnecessary_casts` | Remove unnecessary type casts from source using Eclipse JDT | `path` | `className`, `engine`, `profile`, `attemptTimeoutMillis`, `lineNumbers`, `advancedLookup`, `classpath`, `saveTo` |

## Decompiler Engines

| Engine | Aliases | Description |
|--------|---------|-------------|
| `auto` | — | Multi-engine fallback (default): JD-Core v1 → v0 patching → Vineflower → CFR → Procyon → Fernflower → JADX |
| `jd-core-v1` | `jd-core`, `jdcore`, `jd` | Analytical decompiler |
| `jd-core-v0` | `jdcore-v0`, `v0` | Pattern-matching decompiler (fallback + method patch source) |
| `jd-core-duo` | `jd-duo`, `duo` | v1 with v0 method patching |
| `cfr` | — | Broadly compatible, stable output |
| `procyon` | — | Readable output with line-number options |
| `fernflower` | `ff` | Classic analytical decompiler |
| `vineflower` | `vf` | Most accurate modern Java-focused decompiler |
| `jadx` | — | JVM + Android/DEX-oriented |

**Profile**: `fast`, `accurate`, or `debuggable` — selects appropriate engine defaults and options.

## Common Parameters

| Parameter | Type | Applies To | Description |
|-----------|------|-----------|-------------|
| `engine` | string | decompilation tools | Engine selection (see above) |
| `profile` | string | decompilation tools | `fast`, `accurate`, `debuggable` |
| `releaseVersion` | int | decompilation/metadata tools | Target multi-release class version |
| `attemptTimeoutMillis` | int | decompilation tools | Per-engine attempt timeout (ms), 0 disables |
| `scopePath` | string | search/analysis tools | Multi-archive scope index path |
| `scopeRecursive` | boolean | search/analysis tools | Recursively scan scopePath (default false) |
| `indexPath` | string | search/analysis tools | Custom SQLite index path (default: `~/.jd-mcp-duo/index.sqlite`) |
| `verbose` | boolean | batch decompile tools | Include per-file details in structured result (default false) |
| `descriptor` | string | method-level tools | JVM method descriptor for overload disambiguation, e.g. `(Ljava/lang/String;)V` |
| `classpath` | string/array | decompilation tools | Additional classpath entries |
| `advancedLookup` | boolean | decompilation tools | Search sibling archives for dependency resolution (default false) |
| `lineNumbers` | boolean | decompilation tools | Include line number metadata (default false) |
| `renderLineNumbers` | string | decompilation tools | Render visible line numbers: `decompiled`, `source`, `both`, `none` |
| `writeSidecarMetadata` | boolean | decompilation tools | Write .meta.json sidecar next to source files (default false) |
| `preferences` | object | some decompilation tools | Per-engine raw preferences passed to transformer-api |

## Batch Tool Progress Display

`decompile_directory`, `save_all_sources`, `batch_decompile`, `batch_decompile_jars` show real-time progress on stderr:

```
[jd-mcp-duo] decompile_directory: starting (4556 total)
[jd-mcp-duo] decompile_directory: 1/4556 (0%)
[jd-mcp-duo] decompile_directory -> lib/…/fine-app-core.jar > com.fr…ClassName
```

- Percentage lines: every 1% for 0–10%, then every 5%
- Arrow line: currently processing file (single line, updates in place)

## Supported Formats

`.class`, `.jar`, `.war`, `.ear`, `.kar`, `.zip`, `.jmod`, `.aar`, `.apk`, `.dex`

## SQLite Index Configuration

Default index path: `~/.jd-mcp-duo/index.sqlite`. To avoid filling your system drive, place it in your project directory:

```bash
# Option A: per-tool via --indexPath
search_in_jar --path=./lib --query=MyClass --indexPath=./.jd-mcp-duo/index.sqlite

# Option B: environment variable
JAVA_TOOL_OPTIONS="-Djd.mcp.sqlite.index=./.jd-mcp-duo/index.sqlite"
```

The index supports incremental updates (content fingerprinting). Delete `.jd-mcp-duo/` to clear it.

## Output Path Rules

> **Decompiled output must be saved under the target project path, not the tool directory.**

All `--output`, `--outputDir` parameters should point to the project's audit output directory, e.g.:

```bash
# Correct: output to project audit directory
--output=/target/project/audit/decompiled
--outputDir=/target/project/audit/src
```

In the examples below, `{project}` is the target project root and `{output}` is the audit output directory.

## MCP Mode Notes

- **Large response control**: Batch tools default to `verbose=false` (counts only) to avoid overflowing AI context
- **Liveness check**: Call `help` to confirm the MCP connection is working
- **Progress feedback**: Long-running tasks show progress on stderr (visible in your terminal)
- **Stack size**: Use `-Xss10m` when processing large archives; CLI parameter errors show usage

## Security Audit Usage Examples

### Batch Decompilation

```bash
# 1. Decompile entire WAR preserving directory structure (most common)
jd-mcp-duo save_all_sources --path={project}/app.war --output={output}/app-src

# 2. Decompile all .class files and archives under a directory
jd-mcp-duo decompile_directory --path={project}/WEB-INF --outputDir={output}/decompiled --recursive=true

# 3. Decompile specific classes from a directory root
jd-mcp-duo batch_decompile --path={project}/classes --classes=com.example.Controller,com.example.Service

# 4. Decompile specific classes across multiple JARs
jd-mcp-duo batch_decompile_jars --path={project}/libs --classes=com.example.Dao --recursive=true --pattern="*.jar"

# 5. Decompile and output as sources JAR
jd-mcp-duo save_all_sources --path={project}/app.jar --output={output}/sources.jar --format=sources-jar

# 6. Analyze archive overview with single class preview
jd-mcp-duo decompile_jar --path={project}/app.jar --decompile=true --className=com.example.Main --limit=20
```

### Search & Analysis

```bash
# 7. Search for sensitive strings (passwords, keys, etc.)
jd-mcp-duo search_in_jar --path={project}/app.jar --query=password --type=string --queryMode=wildcard

# 8. Find all Controller classes (wildcard mode)
jd-mcp-duo type_lookup --path={project}/app.jar --query="*Controller" --queryMode=wildcard

# 9. Inspect class metadata (route mappings)
jd-mcp-duo class_metadata --path={project}/app.jar --className=com.example.LoginController

# 10. Trace call chain (Controller → DAO)
jd-mcp-duo call_chain --path={project}/app.jar --className=com.example.Controller --methodName=upload --direction=callees --depth=5

# 11. Find all callers of a method
jd-mcp-duo find_references --path={project}/app.jar --className=com.example.Util --kind=method --methodName=execute

# 12. View method control flow graph (Mermaid format)
jd-mcp-duo show_cfg --path={project}/app.jar --className=com.example.Service --methodName=process --format=mermaid
```

### Dependencies & Diagnostics

```bash
# 13. Extract Maven dependencies for vulnerability matching
jd-mcp-duo list_dependencies --path={project}/app.war --format=text --output={output}/deps.txt

# 14. Build project skeleton with inferred dependencies
jd-mcp-duo build_skeleton --path={project}/libs --outputDir={output}/skeleton

# 15. Look up original source from Maven Central
jd-mcp-duo source_lookup --path={project}/app.jar --className=com.example.Main --saveTo={output}/Main.java

# 16. Decompilation quality report
jd-mcp-duo source_quality_report --path={project}/app.jar

# 17. Resolve stacktrace frames to source lines
jd-mcp-duo resolve_stacktrace --path={project}/app.jar --text="at com.example.Service.process(Service.java:42)"

# 18. Resolve stacktrace from log file (cross-archive)
jd-mcp-duo resolve_stacktrace --path={project}/app.jar --textPath={project}/error.log --scopePath={project}/libs
```
