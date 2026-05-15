package tools;

import index.ScopedClass;
import java.nio.file.Path;
import model.MCPTool;
import model.ToolResult;
import sqlite.PersistentScopeIndex;
import support.GraphSupport;
import support.IndexMetadataSupport;
import support.JsonUtils;
import support.ResolutionSupport;
import support.SchemaSupport;
import support.ToolResults;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Set;
public class TypeHierarchyTool implements MCPTool {
    @Override
    public String getDescription() {
        return "Show supertype and subtype hierarchy for a class across the current input or an optional scope.";
    }

    @Override
    public JsonObject getInputSchema() {
        JsonObject schema = SchemaSupport.objectSchema();
        JsonObject properties = SchemaSupport.properties(schema);
        SchemaSupport.addString(properties, "path", "Primary path");
        SchemaSupport.addString(properties, "className", "Target class name");
        SchemaSupport.addString(properties, "scopePath", "Optional multi-archive scope path");
        SchemaSupport.addBoolean(properties, "scopeRecursive", "Recursively scan scopePath when it is a directory", false);
        SchemaSupport.addString(properties, "indexPath", "Optional path for the SQLite index file; defaults to ~/.jd-mcp-duo/index.sqlite");
        SchemaSupport.addInteger(properties, "depth", "Maximum traversal depth", 8);
        SchemaSupport.addInteger(properties, "maxNodes", "Maximum nodes returned", 256);
        SchemaSupport.require(schema, "path");
        SchemaSupport.require(schema, "className");
        return schema;
    }

    @Override
    public ToolResult execute(JsonObject arguments) throws Exception {
        long startedAt = System.nanoTime();
        Path indexPath = JsonUtils.getPath(arguments, "indexPath");
        PersistentScopeIndex scope = PersistentScopeIndex.open(
                JsonUtils.getRequiredPath(arguments, "path"),
                arguments.has("scopePath") && !JsonUtils.getString(arguments, "scopePath", "").isBlank()
                        ? JsonUtils.getPath(arguments, "scopePath")
                        : null,
                JsonUtils.getBoolean(arguments, "scopeRecursive", false),
                indexPath
        );
        String internalName = JsonUtils.getString(arguments, "className", "").replace('.', '/');
        var matches = scope.resolveClasses(internalName);
        if (matches.isEmpty()) {
            return ResolutionSupport.unresolved("class_not_found", "Class not found: " + internalName.replace('/', '.'));
        }
        ScopedClass root = matches.get(0);
        int depth = JsonUtils.getInt(arguments, "depth", 8);
        int maxNodes = JsonUtils.getInt(arguments, "maxNodes", 256);
        int[] remaining = new int[]{Math.max(1, maxNodes)};

        JsonObject structured = GraphSupport.graphMeta("both", depth, maxNodes);
        structured.addProperty("resolved", true);
        structured.addProperty("indexBackend", "sqlite");
        structured.addProperty("scopeArchiveCount", scope.scopeArchiveCount());
        structured.addProperty("indexPath", scope.databasePath().toString());
        IndexMetadataSupport.addIndexFailureMetadata(structured, scope);
        structured.addProperty("className", root.indexedClass().displayName());
        structured.add("supertypes", buildSuperChain(scope, root, depth, remaining, structured));
        structured.add("subtypes", buildSubtypes(scope, root.indexedClass().internalName(), depth, remaining, structured));
        structured.addProperty("queryMillis", (System.nanoTime() - startedAt) / 1_000_000L);

        StringBuilder text = new StringBuilder();
        text.append("Type hierarchy for ").append(root.indexedClass().displayName()).append('\n');
        text.append("\nSupertypes:\n");
        structured.getAsJsonArray("supertypes").forEach(item -> text.append("- ").append(item.getAsJsonObject().get("displayName").getAsString()).append('\n'));
        text.append("\nSubtypes:\n");
        structured.getAsJsonArray("subtypes").forEach(item -> text.append("- ").append(item.getAsJsonObject().get("displayName").getAsString()).append('\n'));
        return ToolResults.structured(text.toString().trim(), structured);
    }

    private static JsonArray buildSuperChain(PersistentScopeIndex scope, ScopedClass root, int depth, int[] remaining, JsonObject graphMeta) throws Exception {
        JsonArray array = new JsonArray();
        Set<String> seen = new LinkedHashSet<>();
        ArrayDeque<NodeDepth> queue = new ArrayDeque<>();
        if (root.indexedClass().superName() != null) {
            queue.add(new NodeDepth(root.indexedClass().superName(), 1));
        }
        root.indexedClass().interfaces().forEach(type -> queue.add(new NodeDepth(type, 1)));
        while (!queue.isEmpty()) {
            NodeDepth current = queue.removeFirst();
            String type = current.type();
            if (!seen.add(type)) {
                continue;
            }
            JsonObject item = new JsonObject();
            item.addProperty("internalName", type);
            item.addProperty("displayName", type.replace('/', '.'));
            if (!GraphSupport.consumeNode(remaining, graphMeta, item)) {
                array.add(item);
                break;
            }
            array.add(item);
            if (current.depth() >= depth) {
                continue;
            }
            for (ScopedClass scopedClass : scope.resolveClasses(type)) {
                if (scopedClass.indexedClass().superName() != null) {
                    queue.add(new NodeDepth(scopedClass.indexedClass().superName(), current.depth() + 1));
                }
                scopedClass.indexedClass().interfaces().forEach(next -> queue.add(new NodeDepth(next, current.depth() + 1)));
            }
        }
        return array;
    }

    private static JsonArray buildSubtypes(PersistentScopeIndex scope, String rootInternalName, int depth, int[] remaining, JsonObject graphMeta) throws Exception {
        JsonArray array = new JsonArray();
        ArrayDeque<NodeDepth> queue = new ArrayDeque<>();
        queue.add(new NodeDepth(rootInternalName, 0));
        Set<String> seen = new LinkedHashSet<>();
        while (!queue.isEmpty()) {
            NodeDepth current = queue.removeFirst();
            if (!seen.add(current.type())) {
                continue;
            }
            if (current.depth() >= depth) {
                continue;
            }
            for (ScopedClass scopedClass : scope.directSubtypes(current.type())) {
                if (current.type().equals(scopedClass.indexedClass().internalName())) {
                    continue;
                }
                JsonObject item = new JsonObject();
                item.addProperty("sourcePath", scopedClass.sourcePath().toString());
                item.addProperty("internalName", scopedClass.indexedClass().internalName());
                item.addProperty("displayName", scopedClass.indexedClass().displayName());
                if (!GraphSupport.consumeNode(remaining, graphMeta, item)) {
                    array.add(item);
                    return array;
                }
                array.add(item);
                queue.add(new NodeDepth(scopedClass.indexedClass().internalName(), current.depth() + 1));
            }
        }
        return array;
    }

    private record NodeDepth(String type, int depth) {
    }
}
