package tools;

import index.MethodRef;
import index.ScopedMethodRef;
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
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

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
        SchemaSupport.addString(properties, "indexPath", "Optional path for the SQLite index file; defaults to ~/.jd-mcp-duo/index.sqlite");
        SchemaSupport.addBoolean(properties, "noBuild", "Only query existing index; do not build if missing or stale", true);
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
        Path indexPath = JsonUtils.getPath(arguments, "indexPath");
        boolean noBuild = JsonUtils.getBoolean(arguments, "noBuild", true);
        PersistentScopeIndex index = PersistentScopeIndex.open(
                JsonUtils.getRequiredPath(arguments, "path"),
                arguments.has("scopePath") && !JsonUtils.getString(arguments, "scopePath", "").isBlank()
                        ? JsonUtils.getPath(arguments, "scopePath") : null,
                JsonUtils.getBoolean(arguments, "scopeRecursive", false),
                indexPath,
                !noBuild
        );
        String owner = JsonUtils.getString(arguments, "className", "").replace('.', '/');
        String methodName = JsonUtils.getString(arguments, "methodName", "");
        String descriptor = JsonUtils.getString(arguments, "descriptor", null);
        String direction = normalizeDirection(JsonUtils.getString(arguments, "direction", "both"));
        int depth = JsonUtils.getInt(arguments, "depth", 3);
        int maxNodes = JsonUtils.getInt(arguments, "maxNodes", 128);
        if (depth < 1) depth = 1;
        if (maxNodes < 1) maxNodes = 1;

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
        JsonObject structured = GraphSupport.graphMeta(direction, depth, maxNodes);
        structured.addProperty("resolved", true);
        structured.addProperty("indexBackend", "sqlite");
        structured.addProperty("scopeArchiveCount", index.scopeArchiveCount());
        structured.addProperty("indexPath", index.databasePath().toString());
        IndexMetadataSupport.addIndexFailureMetadata(structured, index);
        structured.add("root", methodJson(root));

        StringBuilder text = new StringBuilder();
        text.append("Call chain for ").append(humanSignature(root.methodRef())).append(" @ ").append(root.sourcePath()).append('\n');

        if ("callers".equals(direction) || "both".equals(direction)) {
            text.append("\nCallers:\n");
            int[] callerBudget = {maxNodes};
            JsonObject callersTree = buildTreeBfs(index, root, true, depth, callerBudget, new HashSet<>(), structured, text);
            structured.add("callers", callersTree);
        }
        if ("callees".equals(direction) || "both".equals(direction)) {
            text.append("\nCallees:\n");
            int[] calleeBudget = {maxNodes};
            JsonObject calleesTree = buildTreeBfs(index, root, false, depth, calleeBudget, new HashSet<>(), structured, text);
            structured.add("callees", calleesTree);
        }
        structured.addProperty("queryMillis", (System.nanoTime() - startedAt) / 1_000_000L);

        return ToolResults.structured(text.toString().trim(), structured);
    }

    private static JsonObject buildTreeBfs(PersistentScopeIndex index,
                                            ScopedMethodRef root,
                                            boolean reverse,
                                            int depth,
                                            int[] remaining,
                                            Set<ScopedMethodRef> visited,
                                            JsonObject graphMeta,
                                            StringBuilder text) throws Exception {
        JsonObject rootNode = methodJson(root);
        if (!GraphSupport.consumeNode(remaining, graphMeta, rootNode)) {
            return rootNode;
        }
        rootNode.add("children", new JsonArray());
        text.append(humanSignature(root.methodRef())).append(" @ ").append(root.sourcePath()).append('\n');

        record BfsFrame(ScopedMethodRef method, JsonObject parentNode, int currentDepth, String prefix) {}
        Deque<BfsFrame> queue = new ArrayDeque<>();
        queue.addLast(new BfsFrame(root, rootNode, 0, ""));
        visited.add(root);

        while (!queue.isEmpty() && remaining[0] > 0) {
            BfsFrame frame = queue.removeFirst();
            if (frame.currentDepth() >= depth) {
                continue;
            }

            Set<ScopedMethodRef> neighbors = reverse
                    ? index.incomingScoped(frame.method().methodRef())
                    : index.outgoingScoped(frame.method().methodRef());

            var eligible = new java.util.ArrayList<ScopedMethodRef>();
            for (ScopedMethodRef n : neighbors) {
                if (remaining[0] <= 0) break;
                if (!visited.contains(n)) eligible.add(n);
            }

            for (int idx = 0; idx < eligible.size(); idx++) {
                if (remaining[0] <= 0) {
                    graphMeta.addProperty("truncated", true);
                    break;
                }
                ScopedMethodRef neighbor = eligible.get(idx);
                boolean last = (idx == eligible.size() - 1);
                visited.add(neighbor);

                JsonObject childNode = methodJson(neighbor);
                if (!GraphSupport.consumeNode(remaining, graphMeta, childNode)) {
                    frame.parentNode().getAsJsonArray("children").add(childNode);
                    break;
                }
                childNode.add("children", new JsonArray());
                frame.parentNode().getAsJsonArray("children").add(childNode);

                String branch = last ? "└── " : "├── ";
                text.append(frame.prefix()).append(branch)
                        .append(humanSignature(neighbor.methodRef()))
                        .append(" @ ").append(neighbor.sourcePath()).append('\n');

                if (frame.currentDepth() + 1 < depth) {
                    String childPrefix = frame.prefix() + (last ? "    " : "│   ");
                    queue.addLast(new BfsFrame(neighbor, childNode, frame.currentDepth() + 1, childPrefix));
                }
            }
        }

        if (remaining[0] <= 0) {
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
        var sig = formatSignature(method.descriptor());
        json.addProperty("params", sig.params());
        json.addProperty("returnType", sig.returnType());
        json.addProperty("signature", sig.params() + ": " + sig.returnType());
        json.addProperty("displayName", method.displayName());
        return json;
    }

    private static String humanSignature(MethodRef method) {
        var sig = formatSignature(method.descriptor());
        return method.owner().replace('/', '.') + "." + method.name() + sig.params() + ": " + sig.returnType();
    }

    private record MethodSignature(String params, String returnType) {}

    private static MethodSignature formatSignature(String descriptor) {
        int end = descriptor.indexOf(')');
        if (end < 0) return new MethodSignature("()", "void");
        StringBuilder params = new StringBuilder("(");
        int i = 1;
        while (i < end) {
            if (params.length() > 1) params.append(", ");
            i = appendTypeName(descriptor, i, params);
        }
        params.append(')');
        StringBuilder ret = new StringBuilder();
        if (end + 1 < descriptor.length()) {
            appendTypeName(descriptor, end + 1, ret);
        } else {
            ret.append("void");
        }
        return new MethodSignature(params.toString(), ret.toString());
    }

    private static int appendTypeName(String descriptor, int pos, StringBuilder sb) {
        char ch = descriptor.charAt(pos);
        return switch (ch) {
            case 'B' -> { sb.append("byte");    yield pos + 1; }
            case 'C' -> { sb.append("char");    yield pos + 1; }
            case 'D' -> { sb.append("double");  yield pos + 1; }
            case 'F' -> { sb.append("float");   yield pos + 1; }
            case 'I' -> { sb.append("int");     yield pos + 1; }
            case 'J' -> { sb.append("long");    yield pos + 1; }
            case 'S' -> { sb.append("short");   yield pos + 1; }
            case 'Z' -> { sb.append("boolean"); yield pos + 1; }
            case 'V' -> { sb.append("void");    yield pos + 1; }
            case 'L' -> {
                int end = descriptor.indexOf(';', pos);
                String full = descriptor.substring(pos + 1, end);
                sb.append(full.substring(full.lastIndexOf('/') + 1));
                yield end + 1;
            }
            case '[' -> {
                int nextPos = appendTypeName(descriptor, pos + 1, sb);
                sb.append("[]");
                yield nextPos;
            }
            default -> pos + 1;
        };
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
