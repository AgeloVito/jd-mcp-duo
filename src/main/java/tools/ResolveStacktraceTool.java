package tools;

import archive.InputContainer;
import archive.InputContainers;
import decompile.DecompilationOutcome;
import decompile.DecompilerSession;
import decompile.DecompilerOptions;
import index.ScopedClass;
import model.MCPTool;
import model.ToolResult;
import sqlite.PersistentScopeIndex;
import support.IndexMetadataSupport;
import support.JsonUtils;
import support.SchemaSupport;
import support.ToolResults;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static decompile.DecompilerEngines.AUTO;

public class ResolveStacktraceTool implements MCPTool {
    private static final Pattern FRAME = Pattern.compile("\\s*at\\s+([\\w.$/]+)\\.([\\w$<>]+)\\(([^()]*)\\)");
    private static final Pattern HEADER = Pattern.compile("^(?:Exception in thread \".*?\"\\s+)?([\\w.$]+(?:Exception|Error|Throwable|Failure))(?::\\s*(.*))?$");
    private static final Pattern CAUSED_BY = Pattern.compile("^Caused by:\\s+([\\w.$]+(?:Exception|Error|Throwable|Failure))(?::\\s*(.*))?$");
    private static final Pattern SUPPRESSED = Pattern.compile("^Suppressed:\\s+([\\w.$]+(?:Exception|Error|Throwable|Failure))(?::\\s*(.*))?$");
    private static final Pattern OMITTED = Pattern.compile("^\\.\\.\\.\\s+(\\d+)\\s+more$");
    private static final Pattern LOG_PREFIX = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{3})?)\\s+(TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\\s+(?:\\[([^\\]]+)])?\\s*([\\w.$-]+)?\\s*-\\s*(.*)$");

    @Override
    public String getDescription() {
        return "Resolve Java stacktrace or log frames to classes, methods, candidate archives, and decompiled line mappings.";
    }

    @Override
    public JsonObject getInputSchema() {
        JsonObject schema = SchemaSupport.objectSchema();
        JsonObject properties = SchemaSupport.properties(schema);
        SchemaSupport.addString(properties, "path", "Primary path used as the default scope");
        SchemaSupport.addString(properties, "text", "Raw stacktrace or log text");
        SchemaSupport.addString(properties, "textPath", "Optional path to a .log or text file");
        SchemaSupport.addString(properties, "scopePath", "Optional multi-archive scope path");
        SchemaSupport.addBoolean(properties, "scopeRecursive", "Recursively scan scopePath when it is a directory", false);
        SchemaSupport.addString(properties, "indexPath", "Optional path for the SQLite index file; defaults to ~/.jd-mcp-duo/index.sqlite");
        SchemaSupport.addString(properties, "engine", "Decompiler engine for line mapping");
        SchemaSupport.addInteger(properties, "attemptTimeoutMillis", "Per-engine attempt timeout in milliseconds; 0 disables timeout", (int) decompile.DecompilerOptions.DEFAULT_ATTEMPT_TIMEOUT_MILLIS);
        SchemaSupport.addInteger(properties, "maxFrames", "Maximum number of frame entries to resolve", 200);
        SchemaSupport.addInteger(properties, "lineMappingLimitPerFrame", "Maximum candidate classes with decompiled line mapping per frame; 0 means unlimited", 1);
        SchemaSupport.require(schema, "path");
        return schema;
    }

    @Override
    public ToolResult execute(JsonObject arguments) throws Exception {
        String rawText = loadText(arguments);
        if (rawText == null || rawText.isBlank()) {
            return ToolResults.error("text or textPath is required");
        }
        Path primaryPath = JsonUtils.getRequiredPath(arguments, "path");
        Path scopePath = arguments.has("scopePath") && !JsonUtils.getString(arguments, "scopePath", "").isBlank()
                ? JsonUtils.getPath(arguments, "scopePath")
                : null;
        Path indexPath = JsonUtils.getPath(arguments, "indexPath");
        PersistentScopeIndex scope = PersistentScopeIndex.open(
                primaryPath,
                scopePath,
                JsonUtils.getBoolean(arguments, "scopeRecursive", false),
                indexPath
        );
        DecompilerOptions options = DecompilerOptions.fromArguments(arguments, AUTO);
        Map<Path, InputContainer> containers = new HashMap<>();
        Map<Path, DecompilerSession> sessions = new HashMap<>();
        Map<String, DecompilationOutcome> decompilations = new HashMap<>();
        JsonArray frames = new JsonArray();
        JsonArray entries = new JsonArray();
        StringBuilder text = new StringBuilder();
        int maxFrames = JsonUtils.getInt(arguments, "maxFrames", 200);
        int lineMappingLimitPerFrame = JsonUtils.getInt(arguments, "lineMappingLimitPerFrame", 1);
        int resolvedFrames = 0;
        int frameCount = 0;

        try {
            for (String line : rawText.split("\\R")) {
                JsonObject header = parseHeaderEntry(line);
                if (header != null) {
                    entries.add(header);
                    text.append(header.get("kind").getAsString()).append(": ").append(header.get("exceptionType").getAsString());
                    if (header.has("message")) {
                        text.append(" -> ").append(header.get("message").getAsString());
                    }
                    text.append('\n');
                    continue;
                }

                JsonObject omitted = parseOmittedEntry(line);
                if (omitted != null) {
                    entries.add(omitted);
                    text.append("omitted: ").append(omitted.get("count").getAsInt()).append(" more\n");
                    continue;
                }

                JsonObject logLine = parseLogEntry(line);
                if (logLine != null) {
                    entries.add(logLine);
                    text.append("log: ").append(logLine.get("level").getAsString());
                    if (logLine.has("logger")) {
                        text.append(" ").append(logLine.get("logger").getAsString());
                    }
                    if (logLine.has("message")) {
                        text.append(" -> ").append(logLine.get("message").getAsString());
                    }
                    text.append('\n');
                    continue;
                }

                Matcher matcher = FRAME.matcher(line);
                if (!matcher.matches()) {
                    continue;
                }
                if (frameCount >= maxFrames) {
                    break;
                }
                frameCount++;

                String className = matcher.group(1);
                String methodName = matcher.group(2);
                SourceLocation sourceLocation = parseLocation(matcher.group(3));
                Integer sourceLine = sourceLocation.sourceLine();
                String internalName = className.replace('.', '/');

                JsonObject frame = new JsonObject();
                frame.addProperty("kind", "frame");
                frame.addProperty("className", className);
                frame.addProperty("methodName", methodName);
                if (sourceLocation.fileName() != null) {
                    frame.addProperty("fileName", sourceLocation.fileName());
                }
                if (sourceLine != null) {
                    frame.addProperty("sourceLine", sourceLine);
                }
                if (sourceLocation.nativeMethod()) {
                    frame.addProperty("nativeMethod", true);
                }
                if (sourceLocation.unknownSource()) {
                    frame.addProperty("unknownSource", true);
                }

                JsonArray candidates = new JsonArray();
                int lineMappingsForFrame = 0;
                for (ScopedClass scopedClass : scope.resolveClasses(internalName)) {
                    JsonObject candidate = new JsonObject();
                    candidate.addProperty("sourcePath", scopedClass.sourcePath().toString());
                    candidate.addProperty("displayName", scopedClass.indexedClass().displayName());
                    JsonArray methodCandidates = new JsonArray();
                    scopedClass.indexedClass().methods().stream()
                            .filter(method -> method.ref().name().equals(methodName))
                            .forEach(method -> methodCandidates.add(method.displayName()));
                    candidate.add("methodCandidates", methodCandidates);

                    if (sourceLine != null) {
                        String key = scopedClass.sourcePath() + "#" + internalName;
                        DecompilationOutcome outcome = decompilations.get(key);
                        if (methodCandidates.isEmpty()) {
                            candidate.addProperty("lineMappingAvailable", false);
                            candidate.addProperty("lineMappingSkipped", "method_not_found");
                        } else if (lineMappingLimitPerFrame > 0 && lineMappingsForFrame >= lineMappingLimitPerFrame) {
                            candidate.addProperty("lineMappingAvailable", false);
                            candidate.addProperty("lineMappingSkipped", "limit");
                        } else {
                            lineMappingsForFrame++;
                            if (outcome == null) {
                                InputContainer container = containers.computeIfAbsent(scopedClass.sourcePath(), path -> {
                                    try {
                                        return InputContainers.open(path);
                                    } catch (Exception e) {
                                        throw new RuntimeException(e);
                                    }
                                });
                                JsonObject decompileArgs = arguments.deepCopy();
                                decompileArgs.addProperty("lineNumbers", true);
                                DecompilerOptions lineMappingOptions = DecompilerOptions.fromArguments(decompileArgs, AUTO);
                                DecompilerSession session = sessions.get(scopedClass.sourcePath());
                                if (session == null) {
                                    session = DecompilerSession.open(container, lineMappingOptions);
                                    sessions.put(scopedClass.sourcePath(), session);
                                }
                                outcome = session.decompile(internalName);
                                decompilations.put(key, outcome);
                            }
                            if (outcome.metadataLimited()) {
                                candidate.addProperty("metadataLimited", true);
                                candidate.addProperty("lineMappingAvailable", false);
                            } else {
                                JsonArray decompiledLines = mappedDecompiledLines(outcome, sourceLine);
                                candidate.add("decompiledLines", decompiledLines);
                                candidate.addProperty("lineMappingAvailable", true);
                            }
                        }
                    }

                    candidates.add(candidate);
                }
                frame.add("candidates", candidates);
                frames.add(frame);
                entries.add(frame);
                if (!candidates.isEmpty()) {
                    resolvedFrames++;
                }

                text.append("- ").append(className).append('.').append(methodName);
                if (sourceLine != null) {
                    text.append(':').append(sourceLine);
                } else if (sourceLocation.nativeMethod()) {
                    text.append(" (Native Method)");
                } else if (sourceLocation.unknownSource()) {
                    text.append(" (Unknown Source)");
                }
                text.append(" -> ").append(candidates.size()).append(" candidate(s)\n");
            }
        } finally {
            closeAll(sessions, containers);
        }

        JsonObject structured = new JsonObject();
        structured.addProperty("frameCount", frameCount);
        structured.addProperty("resolvedFrameCount", resolvedFrames);
        structured.addProperty("indexBackend", "sqlite");
        structured.addProperty("scopeArchiveCount", scope.scopeArchiveCount());
        structured.addProperty("indexPath", scope.databasePath().toString());
        IndexMetadataSupport.addIndexFailureMetadata(structured, scope);
        structured.add("frames", frames);
        structured.add("entries", entries);
        return ToolResults.structured(text.toString().trim(), structured);
    }

    static JsonArray mappedDecompiledLines(DecompilationOutcome outcome, int sourceLine) {
        JsonArray decompiledLines = new JsonArray();
        if (outcome == null || outcome.metadataLimited() || outcome.result() == null) {
            return decompiledLines;
        }
        outcome.result().getLineNumbers().forEach((decompiledLine, mappedSourceLine) -> {
            if (mappedSourceLine instanceof Integer value && value == sourceLine) {
                decompiledLines.add((Integer) decompiledLine);
            }
        });
        return decompiledLines;
    }

    private static void closeAll(Map<Path, DecompilerSession> sessions,
                                 Map<Path, InputContainer> containers) throws Exception {
        Exception failure = null;
        for (DecompilerSession session : sessions.values()) {
            try {
                session.close();
            } catch (Exception e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        for (InputContainer container : containers.values()) {
            try {
                container.close();
            } catch (Exception e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static String loadText(JsonObject arguments) throws Exception {
        String inline = JsonUtils.getString(arguments, "text", null);
        if (inline != null && !inline.isBlank()) {
            return inline;
        }
        String textPath = JsonUtils.getString(arguments, "textPath", null);
        if (textPath == null || textPath.isBlank()) {
            return null;
        }
        return java.nio.file.Files.readString(Path.of(textPath).toAbsolutePath().normalize());
    }

    private static JsonObject parseHeaderEntry(String line) {
        Matcher cause = CAUSED_BY.matcher(line.strip());
        if (cause.matches()) {
            return headerEntry("causedBy", cause.group(1), cause.group(2));
        }
        Matcher suppressed = SUPPRESSED.matcher(line.strip());
        if (suppressed.matches()) {
            return headerEntry("suppressed", suppressed.group(1), suppressed.group(2));
        }
        Matcher header = HEADER.matcher(line.strip());
        if (header.matches()) {
            return headerEntry("header", header.group(1), header.group(2));
        }
        return null;
    }

    private static JsonObject parseOmittedEntry(String line) {
        Matcher matcher = OMITTED.matcher(line.strip());
        if (!matcher.matches()) {
            return null;
        }
        JsonObject entry = new JsonObject();
        entry.addProperty("kind", "omitted");
        entry.addProperty("count", Integer.parseInt(matcher.group(1)));
        return entry;
    }

    private static JsonObject headerEntry(String kind, String exceptionType, String message) {
        JsonObject entry = new JsonObject();
        entry.addProperty("kind", kind);
        entry.addProperty("exceptionType", exceptionType);
        if (message != null && !message.isBlank()) {
            entry.addProperty("message", message);
        }
        return entry;
    }

    private static JsonObject parseLogEntry(String line) {
        Matcher matcher = LOG_PREFIX.matcher(line.strip());
        if (!matcher.matches()) {
            return null;
        }
        JsonObject entry = new JsonObject();
        entry.addProperty("kind", "log");
        entry.addProperty("timestamp", matcher.group(1));
        entry.addProperty("level", matcher.group(2));
        if (matcher.group(3) != null && !matcher.group(3).isBlank()) {
            entry.addProperty("thread", matcher.group(3));
        }
        if (matcher.group(4) != null && !matcher.group(4).isBlank()) {
            entry.addProperty("logger", matcher.group(4));
        }
        if (matcher.group(5) != null && !matcher.group(5).isBlank()) {
            entry.addProperty("message", matcher.group(5));
        }
        return entry;
    }

    private static SourceLocation parseLocation(String locationText) {
        String normalized = locationText == null ? "" : locationText.strip();
        if ("Native Method".equals(normalized)) {
            return new SourceLocation(null, null, true, false);
        }
        if ("Unknown Source".equals(normalized)) {
            return new SourceLocation(null, null, false, true);
        }
        int separator = normalized.lastIndexOf(':');
        if (separator > 0 && separator + 1 < normalized.length() && normalized.substring(separator + 1).matches("\\d+")) {
            return new SourceLocation(normalized.substring(0, separator), Integer.parseInt(normalized.substring(separator + 1)), false, false);
        }
        return new SourceLocation(normalized.isBlank() ? null : normalized, null, false, false);
    }

    private record SourceLocation(String fileName, Integer sourceLine, boolean nativeMethod, boolean unknownSource) {
    }
}
