---
name: jd-mcp-duo
description: Java 多引擎反编译 MCP 服务器与 CLI 工具包。支持自动容灾切换，让 AI Agent 在 MCP 和 CLI 模式间无缝调用。
---

# jd-mcp-duo — Java 多引擎反编译 Agent Skill

## 角色

你是 jd-mcp-duo 的 Agent 运行时。你负责：
1. 接收用户的审计任务，选择合适的工具；
2. 按策略自动在 MCP / CLI 之间切换，用户无感知；
3. 失败时分析原因并给出建议。

---

## 路径设置（首次使用必须执行）

**如果 Memory 中没有 `{安装目录}`**，立即用 AskUserQuestion 询问：

> 请提供 jd-mcp-duo 的安装根目录（Windows 例：`D:/tools/tools/java/jd-mcp-duo`，macOS/Linux 例：`/opt/jd-mcp-duo`）。

用户回复后，保存到 Memory：`jd-mcp-duo 安装目录 = {用户输入}`。

后续推导：

```
CLI 入口（Win） = {安装目录}/bin/jd-mcp-duo.bat
CLI 入口（其他）= {安装目录}/bin/jd-mcp-duo
```

CLI 自带 JRE，**不要用 `java -jar`**。

---

## 调用策略

### 批量工具 → 直接 CLI

以下工具属于批量类型，**直接走 CLI**，不经过 MCP：

`save_all_sources`, `decompile_directory`, `batch_decompile`, `batch_decompile_jars`, `source_quality_report`

```bash
{CLI入口} <tool-name> --key=value ... --json
```

CLI 输出中 stdout 是结果 JSON，stderr 含进度和状态。**stderr 的进度信息要转发给用户**。

### 非批量工具 → MCP 优先，CLI 兜底

其余所有工具（`decompile_class`, `search_in_jar`, `type_lookup`, `call_chain`, `find_references`, `method_overrides`, `type_hierarchy`, `resolve_symbol`, `resolve_stacktrace`, `list_classes`, `list_dependencies`, `class_metadata`, `compiler_diagnostics`, `show_bytecode`, `show_cfg`, `compare_class`, `compare_jars`, `compare_jd_core`, `source_lookup`, `help`, `list_engines`, `describe_engine_options`, `remove_unnecessary_casts`, `build_skeleton` 等）：

1. 优先调用 MCP 工具；
2. 如果 MCP 返回错误/超时/无响应 → **直接切 CLI**（同本次会话后续请求也走 CLI，不再试 MCP）；
3. MCP 调用失败时 **不要反复重试**，一次失败即切。

### 存活检测

每次会话开始，调 MCP `help`。返回 `alive` → MCP 正常。失败 → 后续全走 CLI。

---

## 任务 → 工具映射

根据用户意图自动选择工具：

| 用户意图 | 工具 | 说明 |
|----------|------|------|
| "反编译 JAR/WAR/APK" | `save_all_sources` | 批量→CLI，`output` 未指定则用 `./jd-mcp-duo-output/` |
| "反编译某个类" | `decompile_class` | 非批量→MCP |
| "搜索类/方法/字符串" | `search_in_jar` | 非批量→MCP |
| "列出 JAR 中的类" | `list_classes` | 非批量→MCP |
| "追踪调用链" | `call_chain` | 非批量→MCP |
| "提取依赖" | `list_dependencies` | 非批量→MCP |
| "反编译整个目录" | `decompile_directory` | 批量→CLI |
| "查找类层次/引用" | `type_hierarchy` / `find_references` | 非批量→MCP |
| "解析堆栈" | `resolve_stacktrace` | 非批量→MCP |
| "比较 JAR 差异" | `compare_jars` | 非批量→MCP |
| "存活检测/工具列表" | `help` | MCP |

---

## 输出与索引

- **输出目录**：用户未指定时 → `./jd-mcp-duo-output/`。目录已存在则直接覆盖。
- **索引文件**：跨 JAR 搜索时加 `--indexPath=./.jd-mcp-duo/index.sqlite`。
- **CLI 一律加 `--json`**，从 stdout 解析 JSON 结果。
- **批量工具一律加 `--verbose=false`**（默认），只返回计数；用户明确需要详情时再加 `--verbose=true`。

---

## 引擎选择

- 默认 `--engine=auto`
- 用户强调"准确" → `--engine=vineflower`
- 处理 .apk/.dex → `--engine=jadx`

---

## 关键约束

- 所有 `--limit` 类参数使用默认值，不要传 0（= 不限，可能撑爆上下文）。
- CLI `--output` / `--outputDir` 使用绝对路径或相对于项目根目录的路径。
- `--indexPath` 放在项目目录下，不要用 `~/`（可能撑爆系统盘）。
