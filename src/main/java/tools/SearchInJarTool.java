package tools;

import index.IndexedClass;
import index.IndexedField;
import index.IndexedMethod;
import index.ScopedClass;
import index.ScopedResource;
import index.ScopedStringHit;
import model.MCPTool;
import model.ToolResult;
import sqlite.PersistentScopeIndex;
import support.IndexMetadataSupport;
import support.JsonUtils;
import support.SchemaSupport;
import support.ToolResults;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

public class SearchInJarTool implements MCPTool {
    @Override
    public String getDescription() {
        return "Search indexed classes, methods, constructors, fields, and string constants in a supported archive or directory.";
    }

    @Override
    public JsonObject getInputSchema() {
        JsonObject schema = SchemaSupport.objectSchema();
        JsonObject properties = SchemaSupport.properties(schema);
        SchemaSupport.addString(properties, "path", "Path to a supported archive or directory");
        SchemaSupport.addString(properties, "query", "Search query");
        SchemaSupport.addString(properties, "type", "Search type: type, class, constructor, method, field, string, module, resource, xml, properties, service, manifest, yaml, json, all");
        SchemaSupport.addString(properties, "queryMode", "plain, wildcard, or regex");
        SchemaSupport.addBoolean(properties, "caseSensitive", "Enable case-sensitive matching", false);
        SchemaSupport.addString(properties, "scopePath", "Optional multi-archive scope path");
        SchemaSupport.addBoolean(properties, "scopeRecursive", "Recursively scan scopePath when it is a directory", false);
        SchemaSupport.addString(properties, "indexPath", "Optional path for the SQLite index file; defaults to ~/.jd-mcp-duo/index.sqlite");
        SchemaSupport.addBoolean(properties, "distinct", "Deduplicate string results by text", false);
        SchemaSupport.addInteger(properties, "limit", "Maximum number of results", 50);
        SchemaSupport.addInteger(properties, "offset", "Number of results to skip for pagination", 0);
        SchemaSupport.addString(properties, "output", "Optional output file path to write full results");
        SchemaSupport.require(schema, "path");
        SchemaSupport.require(schema, "query");
        return schema;
    }

    @Override
    public ToolResult execute(JsonObject arguments) throws Exception {
        String query = JsonUtils.getString(arguments, "query", "");
        String type = JsonUtils.getString(arguments, "type", "all").toLowerCase();
        String queryMode = JsonUtils.getString(arguments, "queryMode", "plain").toLowerCase();
        boolean caseSensitive = JsonUtils.getBoolean(arguments, "caseSensitive", false);
        boolean distinct = JsonUtils.getBoolean(arguments, "distinct", false);
        int limit = JsonUtils.getInt(arguments, "limit", 50);
        int offset = JsonUtils.getInt(arguments, "offset", 0);
        Path outputPath = extractOutputPath(arguments);
        int effectiveLimit = outputPath != null ? Integer.MAX_VALUE : limit;
        int effectiveOffset = outputPath != null ? 0 : offset;
        long startedAt = System.nanoTime();
        Pattern pattern = buildPattern(query, queryMode, caseSensitive);
        Path primaryPath = JsonUtils.getRequiredPath(arguments, "path");
        Path scopePath = arguments.has("scopePath") && !JsonUtils.getString(arguments, "scopePath", "").isBlank()
                ? JsonUtils.getPath(arguments, "scopePath")
                : null;
        boolean scopeRecursive = JsonUtils.getBoolean(arguments, "scopeRecursive", false);
        Path indexPath = JsonUtils.getPath(arguments, "indexPath");

        PersistentScopeIndex scope = PersistentScopeIndex.open(
                primaryPath,
                scopePath,
                scopeRecursive,
                indexPath,
                true
        );
        JsonArray results = new JsonArray();
        int[] totalMatched = {0};
        StringBuilder text = new StringBuilder();
        text.append("Search results for ").append(query).append("\n\n");

        appendModuleResults(scope, type, pattern, results, text, effectiveLimit, effectiveOffset, totalMatched);
        appendClassResults(scope, type, pattern, results, text, effectiveLimit, effectiveOffset, totalMatched);
        appendFieldResults(scope, type, pattern, results, text, effectiveLimit, effectiveOffset, totalMatched);
        appendMethodResults(scope, type, pattern, results, text, effectiveLimit, effectiveOffset, totalMatched);
        appendStringResults(scope, type, pattern, results, text, effectiveLimit, effectiveOffset, distinct, totalMatched);
        int resourceResults = appendResourceResults(scope, type, pattern, results, text, effectiveLimit, effectiveOffset, null, totalMatched);
        // Performance note: for plain mode on large archives, consider passing
        // likeEncode(query) as entryPathLike to resources() for SQL-level pre-filtering.
        // Currently disabled because queries may match text content, not just path.

        if (outputPath != null) {
            Files.writeString(outputPath, text.toString());
            truncateToPreview(text, results, query, totalMatched);
        }

        JsonObject structured = new JsonObject();
        if (outputPath != null) {
            structured.addProperty("outputFile", outputPath.toString());
        }
        structured.addProperty("query", query);
        structured.addProperty("type", type);
        structured.addProperty("queryMode", queryMode);
        structured.addProperty("caseSensitive", caseSensitive);
        structured.addProperty("indexBackend", "sqlite");
        structured.addProperty("scopeArchiveCount", scope.scopeArchiveCount());
        structured.addProperty("queryMillis", (System.nanoTime() - startedAt) / 1_000_000L);
        structured.addProperty("indexPath", scope.databasePath().toString());
        IndexMetadataSupport.addIndexFailureMetadata(structured, scope);
        structured.addProperty("resourceIndexedResults", resourceResults);
        int _total = totalMatched[0];
        structured.addProperty("totalResults", _total);
        structured.addProperty("offset", offset);
        structured.add("results", results);
        if (results.isEmpty()) {
            text.append("No matching results found.");
        }
        return ToolResults.structured(text.toString().trim(), structured);
    }

    private static int appendResourceResults(PersistentScopeIndex scope,
                                             String type,
                                             Pattern pattern,
                                             JsonArray results,
                                             StringBuilder text,
                                             int limit,
                                             int offset,
                                             String pathLike,
                                             int[] totalMatched) throws Exception {
        if (!matchesType(type, "resource", "xml", "properties", "service", "services", "manifest", "yaml", "yml", "json", "all", "allresources")) {
            return 0;
        }
        int added = 0;
        for (ScopedResource scopedResource : scope.resources(resourceTypesFor(type), pathLike)) {
            var resource = scopedResource.indexedResource();
            boolean pathMatch = pattern.matcher(resource.entryPath()).find();
            Integer lineNumber = null;
            String snippet = null;
            boolean contentMatch = false;
            if (resource.textContent() != null) {
                String[] lines = resource.textContent().split("\\R", -1);
                for (int i = 0; i < lines.length; i++) {
                    if (pattern.matcher(lines[i]).find()) {
                        contentMatch = true;
                        lineNumber = i + 1;
                        snippet = lines[i].strip();
                        break;
                    }
                }
            }
            if (!pathMatch && !contentMatch) {
                continue;
            }
            totalMatched[0]++;
            added++;
            if (totalMatched[0] <= offset || results.size() >= limit) {
                continue;
            }
            JsonObject result = new JsonObject();
            result.addProperty("kind", "resource");
            result.addProperty("sourcePath", scopedResource.sourcePath().toString());
            result.addProperty("entryPath", resource.entryPath());
            result.addProperty("resourceType", resource.resourceType());
            result.addProperty("pathMatch", pathMatch);
            result.addProperty("contentMatch", contentMatch);
            if (lineNumber != null) {
                result.addProperty("lineNumber", lineNumber);
            }
            if (snippet != null) {
                result.addProperty("snippet", snippet);
            }
            results.add(result);
            text.append("[resource] ").append(resource.entryPath())
                    .append(" @ ").append(scopedResource.sourcePath());
            if (lineNumber != null) {
                text.append(':').append(lineNumber);
            }
            if (snippet != null && !snippet.isBlank()) {
                text.append(" -> ").append(snippet);
            }
            text.append('\n');
        }
        return added;
    }

    private static java.util.List<String> resourceTypesFor(String type) {
        return switch (type) {
            case "service", "services" -> java.util.List.of("service");
            case "yaml", "yml" -> java.util.List.of("yaml");
            case "resource", "all", "allresources" -> java.util.List.of();
            default -> java.util.List.of(type);
        };
    }

    private static void appendModuleResults(PersistentScopeIndex scope, String type, Pattern pattern, JsonArray results, StringBuilder text, int limit, int offset, int[] totalMatched) throws Exception {
        if (!matchesType(type, "module", "all")) {
            return;
        }
        for (String module : scope.modules()) {
            if (!pattern.matcher(module).find()) {
                continue;
            }
            totalMatched[0]++;
            if (totalMatched[0] <= offset || results.size() >= limit) {
                continue;
            }
            JsonObject result = new JsonObject();
            result.addProperty("kind", "module");
            result.addProperty("moduleName", module);
            results.add(result);
            text.append("[module] ").append(module).append('\n');
        }
    }

    private static void appendClassResults(PersistentScopeIndex scope, String type, Pattern pattern, JsonArray results, StringBuilder text, int limit, int offset, int[] totalMatched) throws Exception {
        if (!matchesType(type, "type", "class", "all")) {
            return;
        }
        for (ScopedClass scopedClass : scope.classes()) {
            IndexedClass indexedClass = scopedClass.indexedClass();
            if (!pattern.matcher(indexedClass.displayName()).find() && !pattern.matcher(indexedClass.internalName()).find()) {
                continue;
            }
            totalMatched[0]++;
            if (totalMatched[0] <= offset || results.size() >= limit) {
                continue;
            }
            JsonObject result = new JsonObject();
            result.addProperty("kind", "type");
            result.addProperty("sourcePath", scopedClass.sourcePath().toString());
            result.addProperty("className", indexedClass.displayName());
            results.add(result);
            text.append("[type] ").append(indexedClass.displayName()).append(" @ ").append(scopedClass.sourcePath()).append('\n');
        }
    }

    private static void appendFieldResults(PersistentScopeIndex scope, String type, Pattern pattern, JsonArray results, StringBuilder text, int limit, int offset, int[] totalMatched) throws Exception {
        if (!matchesType(type, "field", "all")) {
            return;
        }
        for (ScopedClass scopedClass : scope.classes()) {
            IndexedClass indexedClass = scopedClass.indexedClass();
            for (IndexedField field : indexedClass.fields()) {
                if (!pattern.matcher(field.name()).find() && !pattern.matcher(field.displayName()).find()) {
                    continue;
                }
                totalMatched[0]++;
                if (totalMatched[0] <= offset || results.size() >= limit) {
                    continue;
                }
                JsonObject result = new JsonObject();
                result.addProperty("kind", "field");
                result.addProperty("sourcePath", scopedClass.sourcePath().toString());
                result.addProperty("className", indexedClass.displayName());
                result.addProperty("name", field.name());
                result.addProperty("descriptor", field.descriptor());
                results.add(result);
                text.append("[field] ").append(field.displayName()).append(" : ").append(field.descriptor())
                        .append(" @ ").append(scopedClass.sourcePath()).append('\n');
            }
        }
    }

    private static void appendMethodResults(PersistentScopeIndex scope, String type, Pattern pattern, JsonArray results, StringBuilder text, int limit, int offset, int[] totalMatched) throws Exception {
        if (!matchesType(type, "method", "constructor", "all")) {
            return;
        }
        for (ScopedClass scopedClass : scope.classes()) {
            IndexedClass indexedClass = scopedClass.indexedClass();
            for (IndexedMethod method : indexedClass.methods()) {
                boolean constructor = method.ref().isConstructor();
                if (constructor && !matchesType(type, "constructor", "all")) {
                    continue;
                }
                if (!constructor && !matchesType(type, "method", "all")) {
                    continue;
                }
                String displayName = method.ref().displayName();
                if (!pattern.matcher(method.ref().name()).find() && !pattern.matcher(displayName).find()) {
                    continue;
                }
                totalMatched[0]++;
                if (totalMatched[0] <= offset || results.size() >= limit) {
                    continue;
                }
                JsonObject result = new JsonObject();
                result.addProperty("kind", constructor ? "constructor" : "method");
                result.addProperty("sourcePath", scopedClass.sourcePath().toString());
                result.addProperty("className", indexedClass.displayName());
                result.addProperty("name", method.ref().name());
                result.addProperty("descriptor", method.ref().descriptor());
                results.add(result);
                text.append('[').append(constructor ? "constructor" : "method").append("] ")
                        .append(displayName).append(" @ ").append(scopedClass.sourcePath()).append('\n');
            }
        }
    }

    private static void appendStringResults(PersistentScopeIndex scope, String type, Pattern pattern, JsonArray results, StringBuilder text, int limit, int offset, boolean distinct, int[] totalMatched) throws Exception {
        if (!matchesType(type, "string", "all")) {
            return;
        }
        java.util.HashSet<String> seenTexts = distinct ? new java.util.HashSet<>() : null;
        for (ScopedStringHit scopedHit : scope.strings()) {
            var stringHit = scopedHit.stringHit();
            if (!pattern.matcher(stringHit.text()).find()) {
                continue;
            }
            if (distinct && !seenTexts.add(stringHit.text())) {
                continue;
            }
            totalMatched[0]++;
            if (totalMatched[0] <= offset || results.size() >= limit) {
                continue;
            }
            JsonObject result = new JsonObject();
            result.addProperty("kind", "string");
            result.addProperty("sourcePath", scopedHit.sourcePath().toString());
            result.addProperty("owner", stringHit.owner().replace('/', '.'));
            result.addProperty("methodName", stringHit.methodName());
            result.addProperty("descriptor", stringHit.descriptor());
            result.addProperty("text", stringHit.text());
            results.add(result);
            text.append("[string] ").append(stringHit.owner().replace('/', '.'))
                    .append('#').append(stringHit.methodName())
                    .append(" -> ").append(stringHit.text())
                    .append(" @ ").append(scopedHit.sourcePath()).append('\n');
        }
    }

    private static boolean matchesType(String requestedType, String... candidates) {
        for (String candidate : candidates) {
            if (candidate.equals(requestedType)) {
                return true;
            }
        }
        return false;
    }

    private static Pattern buildPattern(String query, String queryMode, boolean caseSensitive) {
        int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
        return switch (queryMode) {
            case "regex" -> Pattern.compile(query, flags);
            case "wildcard" -> Pattern.compile(wildcardToRegex(query), flags);
            default -> Pattern.compile(Pattern.quote(query), flags);
        };
    }

    private static String wildcardToRegex(String query) {
        StringBuilder regex = new StringBuilder();
        for (char ch : query.toCharArray()) {
            switch (ch) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> {
                    if ("\\.[]{}()+-^$|".indexOf(ch) >= 0) {
                        regex.append('\\');
                    }
                    regex.append(ch);
                }
            }
        }
        return regex.toString();
    }

    private static String likeEncode(String query) {
        StringBuilder like = new StringBuilder();
        like.append('%');
        for (char ch : query.toCharArray()) {
            if (ch == '%' || ch == '_') {
                like.append('\\');
            }
            like.append(ch);
        }
        like.append('%');
        return like.toString();
    }

    private static Path extractOutputPath(JsonObject arguments) {
        String outputStr = JsonUtils.getString(arguments, "output", "");
        if (outputStr.isBlank()) {
            return null;
        }
        return JsonUtils.getPath(arguments, "output");
    }

    private static void truncateToPreview(StringBuilder text, JsonArray results,
                                          String query, int[] totalMatched) {
        int previewCount = Math.min(results.size(), 20);
        JsonArray preview = new JsonArray();
        for (int i = 0; i < previewCount; i++) {
            preview.add(results.get(i));
        }
        while (results.size() > 0) {
            results.remove(results.size() - 1);
        }
        for (int i = 0; i < preview.size(); i++) {
            results.add(preview.get(i));
        }
        StringBuilder previewText = new StringBuilder();
        previewText.append("Search results for ").append(query).append('\n');
        previewText.append("Full results (").append(totalMatched[0]).append(" matches) written to file.\n\n");
        String[] allLines = text.toString().split("\\R");
        int shown = 0;
        for (int i = 2; i < allLines.length && shown < previewCount; i++) {
            previewText.append(allLines[i]).append('\n');
            shown++;
        }
        text.setLength(0);
        text.append(previewText);
    }
}
