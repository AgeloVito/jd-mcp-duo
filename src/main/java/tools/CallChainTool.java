package tools;

import index.MethodRef;
import index.ScopedMethodRef;
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
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class CallChainTool implements MCPTool {
    @Override
    public String getDescription() {
        return "Build a static caller/callee chain for a method inside the current archive or directory.";
    }

    @Override
    public JsonObject getInputSchema() {
        JsonObject schema = SchemaSupport.objectSchema();
        JsonObject properties = SchemaSupport.properties(schema);
        SchemaSupport.addString(properties, "path", "Path to a supported archive or directory");
        SchemaSupport.addString(properties, "className", "Declaring class name");
        SchemaSupport.addString(properties, "methodName", "Method name");
        SchemaSupport.addString(properties, "descriptor", "Optional JVM descriptor for overload resolution");
        SchemaSupport.addString(properties, "direction", "callers, callees, or both");
        SchemaSupport.addString(properties, "scopePath", "Optional multi-archive scope path");
        SchemaSupport.addBoolean(properties, "scopeRecursive", "Recursively scan scopePath when it is a directory", false);
        SchemaSupport.addInteger(properties, "depth", "Recursion depth", 3);
        SchemaSupport.addInteger(properties, "maxNodes", "Maximum nodes returned", 128);
        SchemaSupport.require(schema, "path");
        SchemaSupport.require(schema, "className");
        SchemaSupport.require(schema, "methodName");
        return schema;
    }

    @Override
    public ToolResult execute(JsonObject arguments) throws Exception {
        long startedAt = System.nanoTime();
        PersistentScopeIndex index = PersistentScopeIndex.open(
                JsonUtils.getRequiredPath(arguments, "path"),
                JsonUtils.getPath(arguments, "scopePath"),
                JsonUtils.getBoolean(arguments, "scopeRecursive", false)
        );
        String owner = JsonUtils.getString(arguments, "className", "").replace('.', '/');
        String methodName = JsonUtils.getString(arguments, "methodName", "");
        String descriptor = JsonUtils.getString(arguments, "descriptor", null);
        String direction = normalizeDirection(JsonUtils.getString(arguments, "direction", "both"));
        int depth = JsonUtils.getInt(arguments, "depth", 3);
        int maxNodes = JsonUtils.getInt(arguments, "maxNodes", 128);

        var candidates = index.resolveMethodCandidates(owner, methodName, descriptor);
        var broadCandidates = (descriptor != null && !descriptor.isBlank()) ? index.resolveMethodCandidates(owner, methodName, null) : candidates;
        if (candidates.isEmpty()) {
            return ResolutionSupport.unresolvedMethod(owner, methodName, descriptor, broadCandidates);
        }
        if ((descriptor == null || descriptor.isBlank()) && candidates.size() > 1) {
            return ResolutionSupport.unresolvedMethod(owner, methodName, descriptor, candidates);
        }
        if (descriptor != null && !descriptor.isBlank() && candidates.size() > 1) {
            return ResolutionSupport.unresolvedMethodSourceAmbiguous(owner, methodName, descriptor, candidates);
        }

        ScopedMethodRef root = new ScopedMethodRef(candidates.get(0).sourcePath(), candidates.get(0).indexedMethod().ref());
        AtomicInteger remaining = new AtomicInteger(Math.max(1, maxNodes));
        JsonObject structured = GraphSupport.graphMeta(direction, depth, maxNodes);
        structured.addProperty("resolved", true);
        structured.addProperty("indexBackend", "sqlite");
        structured.addProperty("scopeArchiveCount", index.scopeArchiveCount());
        structured.addProperty("indexPath", index.databasePath().toString());
        IndexMetadataSupport.addIndexFailureMetadata(structured, index);
        structured.add("root", methodJson(root));

        StringBuilder text = new StringBuilder();
        text.append("Call chain for ").append(root.methodRef().displayName()).append(" @ ").append(root.sourcePath()).append('\n');

        if ("callers".equals(direction) || "both".equals(direction)) {
            text.append("\nCallers:\n");
            JsonObject callersTree = buildTreeBfs(index, root, true, depth, remaining, new HashSet<>(), structured, text);
            structured.add("callers", callersTree);
        }
        if ("callees".equals(direction) || "both".equals(direction)) {
            text.append("\nCallees:\n");
            JsonObject calleesTree = buildTreeBfs(index, root, false, depth, remaining, new HashSet<>(), structured, text);
            structured.add("callees", calleesTree);
        }
        structured.addProperty("queryMillis", (System.nanoTime() - startedAt) / 1_000_000L);

        return ToolResults.structured(text.toString().trim(), structured);
    }

    private static JsonObject buildTreeBfs(PersistentScopeIndex index,
                                            ScopedMethodRef root,
                                            boolean reverse,
                                            int depth,
                                            AtomicInteger remaining,
                                            Set<ScopedMethodRef> visited,
                                            JsonObject graphMeta,
                                            StringBuilder text) throws Exception {
        JsonObject rootNode = methodJson(root);
        if (!GraphSupport.consumeNode(remaining, graphMeta, rootNode)) {
            return rootNode;
        }
        rootNode.add("children", new JsonArray());
        text.append("- ").append(root.methodRef().displayName()).append(" @ ").append(root.sourcePath()).append('\n');

        record BfsFrame(ScopedMethodRef method, JsonObject parentNode, int currentDepth) {}
        Deque<BfsFrame> queue = new ArrayDeque<>();
        queue.addLast(new BfsFrame(root, rootNode, 0));
        visited.add(root);

        while (!queue.isEmpty() && remaining.get() > 0 && depth > 0) {
            BfsFrame frame = queue.removeFirst();
            if (frame.currentDepth() >= depth) {
                continue;
            }

            Set<ScopedMethodRef> neighbors = reverse
                    ? index.incomingScoped(frame.method().methodRef())
                    : index.outgoingScoped(frame.method().methodRef());

            for (ScopedMethodRef neighbor : neighbors) {
                if (remaining.get() <= 0) {
                    graphMeta.addProperty("truncated", true);
                    break;
                }
                if (visited.contains(neighbor)) {
                    continue;
                }
                visited.add(neighbor);

                JsonObject childNode = methodJson(neighbor);
                if (!GraphSupport.consumeNode(remaining, graphMeta, childNode)) {
                    frame.parentNode().getAsJsonArray("children").add(childNode);
                    break;
                }
                childNode.add("children", new JsonArray());
                frame.parentNode().getAsJsonArray("children").add(childNode);
                text.append("  ".repeat(frame.currentDepth() + 1))
                        .append("- ").append(neighbor.methodRef().displayName())
                        .append(" @ ").append(neighbor.sourcePath()).append('\n');

                if (frame.currentDepth() + 1 < depth) {
                    queue.addLast(new BfsFrame(neighbor, childNode, frame.currentDepth() + 1));
                }
            }
        }

        if (remaining.get() <= 0) {
            graphMeta.addProperty("truncated", true);
        }
        return rootNode;
    }

    private static JsonObject methodJson(ScopedMethodRef scopedMethod) {
        MethodRef method = scopedMethod.methodRef();
        JsonObject json = new JsonObject();
        json.addProperty("sourcePath", scopedMethod.sourcePath().toString());
        json.addProperty("owner", method.owner());
        json.addProperty("displayOwner", method.owner().replace('/', '.'));
        json.addProperty("name", method.name());
        json.addProperty("descriptor", method.descriptor());
        json.addProperty("displayName", method.displayName());
        return json;
    }

    private static String normalizeDirection(String direction) {
        String normalized = direction == null ? "both" : direction.trim().toLowerCase();
        return switch (normalized) {
            case "caller", "callers" -> "callers";
            case "callee", "callees" -> "callees";
            case "both" -> "both";
            default -> "both";
        };
    }
}
