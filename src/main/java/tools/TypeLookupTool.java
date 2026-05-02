package tools;

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
        SchemaSupport.addInteger(properties, "limit", "Maximum results", 50);
        SchemaSupport.require(schema, "path");
        SchemaSupport.require(schema, "query");
        return schema;
    }

    @Override
    public ToolResult execute(JsonObject arguments) throws Exception {
        String query = JsonUtils.getString(arguments, "query", "");
        String queryMode = JsonUtils.getString(arguments, "queryMode", "plain");
        int limit = JsonUtils.getInt(arguments, "limit", 50);
        long startedAt = System.nanoTime();
        Pattern pattern = pattern(query, queryMode);

        PersistentScopeIndex scope = PersistentScopeIndex.open(
                JsonUtils.getRequiredPath(arguments, "path"),
                arguments.has("scopePath") && !JsonUtils.getString(arguments, "scopePath", "").isBlank()
                        ? JsonUtils.getPath(arguments, "scopePath")
                        : null,
                JsonUtils.getBoolean(arguments, "scopeRecursive", false)
        );
        JsonArray matches = new JsonArray();
        StringBuilder text = new StringBuilder();
        text.append("Type lookup for ").append(query).append("\n\n");
        for (ScopedClass scopedClass : scope.classes()) {
            if (matches.size() >= limit) {
                break;
            }
            if (!pattern.matcher(scopedClass.indexedClass().displayName()).find()
                    && !pattern.matcher(scopedClass.indexedClass().internalName()).find()) {
                continue;
            }
            JsonObject item = new JsonObject();
            item.addProperty("sourcePath", scopedClass.sourcePath().toString());
            item.addProperty("internalName", scopedClass.indexedClass().internalName());
            item.addProperty("displayName", scopedClass.indexedClass().displayName());
            matches.add(item);
            text.append("- ").append(scopedClass.indexedClass().displayName())
                    .append(" @ ").append(scopedClass.sourcePath()).append('\n');
        }
        JsonObject structured = new JsonObject();
        structured.addProperty("query", query);
        structured.addProperty("indexBackend", "sqlite");
        structured.addProperty("scopeArchiveCount", scope.scopeArchiveCount());
        structured.addProperty("queryMillis", (System.nanoTime() - startedAt) / 1_000_000L);
        structured.addProperty("indexPath", scope.databasePath().toString());
        IndexMetadataSupport.addIndexFailureMetadata(structured, scope);
        structured.add("matches", matches);
        return ToolResults.structured(text.toString().trim(), structured);
    }

    private static Pattern pattern(String query, String queryMode) {
        return switch (queryMode.toLowerCase()) {
            case "regex" -> Pattern.compile(query, Pattern.CASE_INSENSITIVE);
            case "wildcard" -> Pattern.compile(query.replace(".", "\\.").replace("*", ".*").replace("?", "."), Pattern.CASE_INSENSITIVE);
            default -> Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE);
        };
    }
}
