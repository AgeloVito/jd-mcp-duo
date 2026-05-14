package tools;

import index.FieldRef;
import index.MethodRef;
import index.ScopedField;
import index.ScopedMethod;
import index.ScopedMethodRef;
import index.TypeReferenceHit;
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

import java.util.LinkedHashSet;
import java.util.Set;
public class FindReferencesTool implements MCPTool {
    @Override
    public String getDescription() {
        return "Find references to a type, field, or method across the current input or an optional scope.";
    }

    @Override
    public JsonObject getInputSchema() {
        JsonObject schema = SchemaSupport.objectSchema();
        JsonObject properties = SchemaSupport.properties(schema);
        SchemaSupport.addString(properties, "path", "Primary path");
        SchemaSupport.addString(properties, "kind", "type, field, or method");
        SchemaSupport.addString(properties, "className", "Target class name");
        SchemaSupport.addString(properties, "fieldName", "Target field name");
        SchemaSupport.addString(properties, "methodName", "Target method name");
        SchemaSupport.addString(properties, "descriptor", "Optional descriptor");
        SchemaSupport.addString(properties, "scopePath", "Optional multi-archive scope path");
        SchemaSupport.addBoolean(properties, "scopeRecursive", "Recursively scan scopePath when it is a directory", false);
        SchemaSupport.addInteger(properties, "depth", "Maximum traversal depth for graph-style kinds", 1);
        SchemaSupport.addInteger(properties, "maxNodes", "Maximum nodes returned", 256);
        SchemaSupport.require(schema, "path");
        SchemaSupport.require(schema, "kind");
        SchemaSupport.require(schema, "className");
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
        String kind = JsonUtils.getString(arguments, "kind", "type").toLowerCase();
        String owner = JsonUtils.getString(arguments, "className", "").replace('.', '/');
        String fieldName = JsonUtils.getString(arguments, "fieldName", null);
        String methodName = JsonUtils.getString(arguments, "methodName", null);
        String descriptor = JsonUtils.getString(arguments, "descriptor", null);
        int depth = JsonUtils.getInt(arguments, "depth", 1);
        int maxNodes = JsonUtils.getInt(arguments, "maxNodes", 256);
        int[] remaining = new int[]{Math.max(1, maxNodes)};

        JsonArray refs = new JsonArray();
        StringBuilder text = new StringBuilder();
        text.append("References for ").append(owner.replace('/', '.')).append('\n');
        JsonObject structured = GraphSupport.graphMeta(kind, depth, maxNodes);
        structured.addProperty("resolved", true);
        structured.addProperty("indexBackend", "sqlite");
        structured.addProperty("scopeArchiveCount", scope.scopeArchiveCount());
        structured.addProperty("indexPath", scope.databasePath().toString());
        IndexMetadataSupport.addIndexFailureMetadata(structured, scope);

        switch (kind) {
            case "type" -> {
                for (TypeReferenceHit hit : scope.typeReferences(owner)) {
                    if (remaining[0] <= 0) {
                        structured.addProperty("truncated", true);
                        break;
                    }
                    JsonObject item = new JsonObject();
                    item.addProperty("sourceOwner", displaySourceOwner(hit.sourceOwner()));
                    if (hit.sourceOwner().startsWith("resource:")) {
                        item.addProperty("resourceEntry", hit.sourceOwner().substring("resource:".length()));
                    }
                    item.addProperty("sourceMemberName", hit.sourceMemberName());
                    item.addProperty("sourceMemberDescriptor", hit.sourceMemberDescriptor());
                    item.addProperty("kind", hit.kind());
                    if (!GraphSupport.consumeNode(remaining, structured, item)) {
                        refs.add(item);
                        break;
                    }
                    refs.add(item);
                    text.append("- ").append(displaySourceOwner(hit.sourceOwner()));
                    if (hit.sourceMemberName() != null) {
                        if (hit.sourceOwner().startsWith("resource:")) {
                            text.append(" @ ").append(hit.sourceMemberName());
                        } else {
                            text.append('#').append(hit.sourceMemberName()).append(hit.sourceMemberDescriptor());
                        }
                    }
                    text.append(" [").append(hit.kind()).append("]\n");
                }
            }
            case "method" -> {
                var methods = scope.resolveMethodCandidates(owner, methodName, descriptor);
                var broadMethods = (descriptor != null && !descriptor.isBlank())
                        ? scope.resolveMethodCandidates(owner, methodName, null)
                        : methods;
                if (methods.isEmpty() || ((descriptor == null || descriptor.isBlank()) && methods.size() > 1)) {
                    return ResolutionSupport.unresolvedMethod(owner, methodName, descriptor, methods.isEmpty() ? broadMethods : methods);
                }
                if (descriptor != null && !descriptor.isBlank() && methods.size() > 1) {
                    return ResolutionSupport.unresolvedMethodSourceAmbiguous(owner, methodName, descriptor, methods);
                }
                structured.addProperty("targetDescriptor", methods.get(0).indexedMethod().ref().descriptor());
                appendMethodRefs(scope.incomingScoped(methods.get(0).indexedMethod().ref()), refs, text, remaining, structured);
            }
            case "field" -> {
                var fields = scope.resolveFieldCandidates(owner, fieldName, descriptor);
                var broadFields = (descriptor != null && !descriptor.isBlank())
                        ? scope.resolveFieldCandidates(owner, fieldName, null)
                        : fields;
                if (fields.isEmpty() || ((descriptor == null || descriptor.isBlank()) && fields.size() > 1)) {
                    return ResolutionSupport.unresolvedField(owner, fieldName, descriptor, fields.isEmpty() ? broadFields : fields);
                }
                if (descriptor != null && !descriptor.isBlank() && fields.size() > 1) {
                    return ResolutionSupport.unresolvedFieldSourceAmbiguous(owner, fieldName, descriptor, fields);
                }
                structured.addProperty("targetDescriptor", fields.get(0).indexedField().descriptor());
                appendMethodRefs(scope.incomingScoped(new FieldRef(fields.get(0).indexedField().owner(), fields.get(0).indexedField().name(), fields.get(0).indexedField().descriptor())), refs, text, remaining, structured);
            }
            default -> {
                return ToolResults.error("Unsupported kind: " + kind);
            }
        }

        structured.addProperty("kind", kind);
        structured.addProperty("queryMillis", (System.nanoTime() - startedAt) / 1_000_000L);
        structured.add("references", refs);
        return ToolResults.structured(text.toString().trim(), structured);
    }

    private static void appendMethodRefs(Set<ScopedMethodRef> refsSet,
                                         JsonArray refs,
                                         StringBuilder text,
                                         int[] remaining,
                                         JsonObject structured) {
        Set<ScopedMethodRef> seen = new LinkedHashSet<>();
        for (ScopedMethodRef scopedRef : refsSet) {
            if (!seen.add(scopedRef)) {
                continue;
            }
            MethodRef ref = scopedRef.methodRef();
            JsonObject item = methodRefJson(ref);
            item.addProperty("sourcePath", scopedRef.sourcePath().toString());
            if (!GraphSupport.consumeNode(remaining, structured, item)) {
                refs.add(item);
                break;
            }
            refs.add(item);
            text.append("- ").append(ref.displayName()).append(" @ ").append(scopedRef.sourcePath()).append('\n');
        }
    }

    private static JsonObject methodRefJson(MethodRef methodRef) {
        JsonObject item = new JsonObject();
        item.addProperty("owner", methodRef.owner());
        item.addProperty("displayOwner", methodRef.owner().replace('/', '.'));
        item.addProperty("name", methodRef.name());
        item.addProperty("descriptor", methodRef.descriptor());
        item.addProperty("displayName", methodRef.displayName());
        return item;
    }

    private static String displaySourceOwner(String sourceOwner) {
        return sourceOwner.startsWith("resource:")
                ? sourceOwner.substring("resource:".length())
                : sourceOwner.replace('/', '.');
    }
}
