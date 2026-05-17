package tools;

import index.ScopedClass;
import java.nio.file.Files;
import java.nio.file.Path;
import model.MCPTool;
import model.ToolResult;
import sqlite.PersistentScopeIndex;
import support.IndexMetadataSupport;
import support.JsonUtils;
import support.SchemaSupport;
import support.ToolResults;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.regex.Pattern;

public class TypeLookupTool implements MCPTool {
    @Override
    public String getDescription() {
        return "Find matching classes by exact name, wildcard, or regex across the current input or an optional scope.";
    }

    @Override
    public JsonObject getInputSchema() {
        JsonObject schema = SchemaSupport.objectSchema();
        JsonObject properties = SchemaSupport.properties(schema);
        SchemaSupport.addString(properties, "path", "Primary path");
        SchemaSupport.addString(properties, "query", "Class lookup query");
        SchemaSupport.addString(properties, "queryMode", "plain, wildcard, or regex");
        SchemaSupport.addString(properties, "scopePath", "Optional multi-archive scope path");
        SchemaSupport.addBoolean(properties, "scopeRecursive", "Recursively scan scopePath when it is a directory", false);
        SchemaSupport.addString(properties, "indexPath", "Optional path for the SQLite index file; defaults to ~/.jd-mcp-duo/index.sqlite");
        SchemaSupport.addBoolean(properties, "caseSensitive", "Enable case-sensitive matching", false);
        SchemaSupport.addInteger(properties, "limit", "Maximum results", 50);
        SchemaSupport.addInteger(properties, "offset", "Number of results to skip for pagination", 0);
        SchemaSupport.addString(properties, "output", "Optional output file path to write full results");
        SchemaSupport.require(schema, "path");
        SchemaSupport.require(schema, "query");
        return schema;
    }

    @Override
    public ToolResult execute(JsonObject arguments) throws Exception {
        String query = JsonUtils.getString(arguments, "query", "");
        String queryMode = JsonUtils.getString(arguments, "queryMode", "plain");
        boolean caseSensitive = JsonUtils.getBoolean(arguments, "caseSensitive", false);
        int limit = JsonUtils.getInt(arguments, "limit", 50);
        int offset = JsonUtils.getInt(arguments, "offset", 0);
        Path outputPath = extractOutputPath(arguments);
        int effectiveLimit = outputPath != null ? Integer.MAX_VALUE : limit;
        int effectiveOffset = outputPath != null ? 0 : offset;
        long startedAt = System.nanoTime();
        Pattern pattern = buildPattern(query, queryMode, caseSensitive);

        Path indexPath = JsonUtils.getPath(arguments, "indexPath");
        PersistentScopeIndex scope = PersistentScopeIndex.open(
                JsonUtils.getRequiredPath(arguments, "path"),
                arguments.has("scopePath") && !JsonUtils.getString(arguments, "scopePath", "").isBlank()
                        ? JsonUtils.getPath(arguments, "scopePath")
                        : null,
                JsonUtils.getBoolean(arguments, "scopeRecursive", false),
                indexPath,
                true
        );
        JsonArray matches = new JsonArray();
        StringBuilder text = new StringBuilder();
        text.append("Type lookup for ").append(query).append("\n\n");
        int[] totalMatched = {0};
        for (ScopedClass scopedClass : scope.classes()) {
            if (!pattern.matcher(scopedClass.indexedClass().displayName()).find()
                    && !pattern.matcher(scopedClass.indexedClass().internalName()).find()) {
                continue;
            }
            totalMatched[0]++;
            if (totalMatched[0] > effectiveOffset && matches.size() < effectiveLimit) {
                JsonObject item = new JsonObject();
                item.addProperty("sourcePath", scopedClass.sourcePath().toString());
                item.addProperty("internalName", scopedClass.indexedClass().internalName());
                item.addProperty("displayName", scopedClass.indexedClass().displayName());
                matches.add(item);
                text.append("- ").append(scopedClass.indexedClass().displayName())
                        .append(" @ ").append(scopedClass.sourcePath()).append('\n');
            }
        }
        JsonObject structured = new JsonObject();
        structured.addProperty("query", query);
        structured.addProperty("indexBackend", "sqlite");
        structured.addProperty("scopeArchiveCount", scope.scopeArchiveCount());
        structured.addProperty("queryMillis", (System.nanoTime() - startedAt) / 1_000_000L);
        structured.addProperty("indexPath", scope.databasePath().toString());
        IndexMetadataSupport.addIndexFailureMetadata(structured, scope);
        if (outputPath != null) {
            Files.writeString(outputPath, text.toString());
            truncateToPreview(text, matches, query, totalMatched);
        }

        int _total = totalMatched[0];
        structured.addProperty("totalResults", _total);
        structured.addProperty("offset", offset);
        if (outputPath != null) {
            structured.addProperty("outputFile", outputPath.toString());
        }
        structured.add("matches", matches);
        return ToolResults.structured(text.toString().trim(), structured);
    }

    private static Pattern buildPattern(String query, String queryMode, boolean caseSensitive) {
        int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
        return switch (queryMode.toLowerCase()) {
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

    private static Path extractOutputPath(JsonObject arguments) {
        String outputStr = JsonUtils.getString(arguments, "output", "");
        return outputStr.isBlank() ? null : JsonUtils.getPath(arguments, "output");
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
        previewText.append("Type lookup for ").append(query).append('\n');
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
