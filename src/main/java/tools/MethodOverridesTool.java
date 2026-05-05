package tools;

import index.ScopedClass;
import index.ScopedMethod;
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
import java.util.concurrent.atomic.AtomicInteger;

public class MethodOverridesTool implements MCPTool {
    @Override
    public String getDescription() {
        return "Find override and implementation relationships for a method across the current input or an optional scope.";
    }

    @Override
    public JsonObject getInputSchema() {
        JsonObject schema = SchemaSupport.objectSchema();
        JsonObject properties = SchemaSupport.properties(schema);
        SchemaSupport.addString(properties, "path", "Primary path");
        SchemaSupport.addString(properties, "className", "Declaring class name");
        SchemaSupport.addString(properties, "methodName", "Method name");
        SchemaSupport.addString(properties, "descriptor", "Optional descriptor");
        SchemaSupport.addString(properties, "scopePath", "Optional multi-archive scope path");
        SchemaSupport.addBoolean(properties, "scopeRecursive", "Recursively scan scopePath when it is a directory", false);
        SchemaSupport.addInteger(properties, "depth", "Maximum traversal depth", 8);
        SchemaSupport.addInteger(properties, "maxNodes", "Maximum nodes returned", 256);
        SchemaSupport.require(schema, "path");
        SchemaSupport.require(schema, "className");
        SchemaSupport.require(schema, "methodName");
        return schema;
    }

    @Override
    public ToolResult execute(JsonObject arguments) throws Exception {
        long startedAt = System.nanoTime();
        PersistentScopeIndex scope = PersistentScopeIndex.open(
                JsonUtils.getRequiredPath(arguments, "path"),
                arguments.has("scopePath") && !JsonUtils.getString(arguments, "scopePath", "").isBlank()
                        ? JsonUtils.getPath(arguments, "scopePath")
                        : null,
                JsonUtils.getBoolean(arguments, "scopeRecursive", false)
        );
        String owner = JsonUtils.getString(arguments, "className", "").replace('.', '/');
        String methodName = JsonUtils.getString(arguments, "methodName", "");
        String descriptor = JsonUtils.getString(arguments, "descriptor", null);
        int depth = JsonUtils.getInt(arguments, "depth", 8);
        int maxNodes = JsonUtils.getInt(arguments, "maxNodes", 256);

        var roots = scope.resolveMethodCandidates(owner, methodName, descriptor);
        var broadRoots = (descriptor != null && !descriptor.isBlank())
                ? scope.resolveMethodCandidates(owner, methodName, null)
                : roots;
        if (roots.isEmpty()) {
            return ResolutionSupport.unresolvedMethod(owner, methodName, descriptor, broadRoots);
        }
        if ((descriptor == null || descriptor.isBlank()) && roots.size() > 1) {
            return ResolutionSupport.unresolvedMethod(owner, methodName, descriptor, roots);
        }

        String desc = roots.get(0).indexedMethod().ref().descriptor();
        Set<String> relatedTypes = relatedTypes(scope, owner, depth);
        AtomicInteger remaining = new AtomicInteger(Math.max(1, maxNodes));
        JsonArray methods = new JsonArray();
        JsonObject structured = GraphSupport.graphMeta("overrides", depth, maxNodes);
        StringBuilder text = new StringBuilder();
        text.append("Overrides for ").append(owner.replace('/', '.')).append('#').append(methodName).append(desc).append('\n');
        for (String type : relatedTypes) {
            for (ScopedMethod candidate : scope.resolveMethodCandidates(type, methodName, desc)) {
                JsonObject item = new JsonObject();
                item.addProperty("sourcePath", candidate.sourcePath().toString());
                item.addProperty("owner", candidate.indexedClass().displayName());
                item.addProperty("descriptor", candidate.indexedMethod().ref().descriptor());
                if (!GraphSupport.consumeNode(remaining, structured, item)) {
                    methods.add(item);
                    structured.addProperty("resolved", true);
                    structured.addProperty("truncated", true);
                    structured.addProperty("matchCount", methods.size());
                    structured.addProperty("indexBackend", "sqlite");
                    structured.addProperty("scopeArchiveCount", scope.scopeArchiveCount());
                    structured.addProperty("queryMillis", (System.nanoTime() - startedAt) / 1_000_000L);
                    structured.addProperty("indexPath", scope.databasePath().toString());
                    IndexMetadataSupport.addIndexFailureMetadata(structured, scope);
                    structured.add("matches", methods);
                    return ToolResults.structured(text.toString().trim(), structured);
                }
                methods.add(item);
                text.append("- ").append(candidate.indexedMethod().displayName())
                        .append(" @ ").append(candidate.sourcePath()).append('\n');
            }
        }
        structured.addProperty("resolved", true);
        structured.addProperty("matchCount", methods.size());
        structured.addProperty("indexBackend", "sqlite");
        structured.addProperty("scopeArchiveCount", scope.scopeArchiveCount());
        structured.addProperty("queryMillis", (System.nanoTime() - startedAt) / 1_000_000L);
        structured.addProperty("indexPath", scope.databasePath().toString());
        IndexMetadataSupport.addIndexFailureMetadata(structured, scope);
        structured.add("matches", methods);
        return ToolResults.structured(text.toString().trim(), structured);
    }

    private static Set<String> relatedTypes(PersistentScopeIndex scope, String root, int maxDepth) throws Exception {
        Set<String> related = new LinkedHashSet<>();
        ArrayDeque<NodeDepth> queue = new ArrayDeque<>();
        queue.add(new NodeDepth(root, 0));
        while (!queue.isEmpty()) {
            NodeDepth currentNode = queue.removeFirst();
            String current = currentNode.type();
            if (!related.add(current)) {
                continue;
            }
            if (currentNode.depth() >= maxDepth) {
                continue;
            }
            for (ScopedClass scopedClass : scope.classHeaders(current)) {
                if (scopedClass.indexedClass().superName() != null) {
                    queue.add(new NodeDepth(scopedClass.indexedClass().superName(), currentNode.depth() + 1));
                }
                scopedClass.indexedClass().interfaces().forEach(type -> queue.add(new NodeDepth(type, currentNode.depth() + 1)));
            }
            for (String subtype : scope.directSubtypeInternalNames(current)) {
                queue.add(new NodeDepth(subtype, currentNode.depth() + 1));
            }
        }
        return related;
    }

    private record NodeDepth(String type, int depth) {
    }
}
