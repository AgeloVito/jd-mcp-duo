package tools;

import index.ScopedClass;
import index.ScopedField;
import index.ScopedMethod;
import java.nio.file.Path;
import model.MCPTool;
import model.ToolResult;
import sqlite.PersistentScopeIndex;
import support.IndexMetadataSupport;
import support.JsonUtils;
import support.ResolutionSupport;
import support.SchemaSupport;
import support.ToolResults;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class ResolveSymbolTool implements MCPTool {
    @Override
    public String getDescription() {
        return "Resolve type, field, or method symbols to internal names, descriptors, and matching declarations.";
    }

    @Override
    public JsonObject getInputSchema() {
        JsonObject schema = SchemaSupport.objectSchema();
        JsonObject properties = SchemaSupport.properties(schema);
        SchemaSupport.addString(properties, "path", "Primary path");
        SchemaSupport.addString(properties, "className", "Declaring class name");
        SchemaSupport.addString(properties, "fieldName", "Field name");
        SchemaSupport.addString(properties, "methodName", "Method name");
        SchemaSupport.addString(properties, "descriptor", "Optional descriptor");
        SchemaSupport.addString(properties, "scopePath", "Optional multi-archive scope path");
        SchemaSupport.addBoolean(properties, "scopeRecursive", "Recursively scan scopePath when it is a directory", false);
        SchemaSupport.addString(properties, "indexPath", "Optional path for the SQLite index file; defaults to ~/.jd-mcp-duo/index.sqlite");
        SchemaSupport.require(schema, "path");
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
                indexPath,
                false
        );
        String className = JsonUtils.getString(arguments, "className", null);
        String fieldName = JsonUtils.getString(arguments, "fieldName", null);
        String methodName = JsonUtils.getString(arguments, "methodName", null);
        String descriptor = JsonUtils.getString(arguments, "descriptor", null);

        JsonObject structured = new JsonObject();
        StringBuilder text = new StringBuilder();

        if (className != null && !className.isBlank()) {
            String owner = className.replace('.', '/');
            JsonArray types = new JsonArray();
            for (ScopedClass scopedClass : scope.resolveClasses(owner)) {
                JsonObject item = new JsonObject();
                item.addProperty("sourcePath", scopedClass.sourcePath().toString());
                item.addProperty("internalName", scopedClass.indexedClass().internalName());
                item.addProperty("displayName", scopedClass.indexedClass().displayName());
                types.add(item);
            }
            if (types.isEmpty()) {
                return ResolutionSupport.unresolved("class_not_found", "Class not found: " + className);
            }
            structured.add("types", types);
            text.append("Types:\n");
            types.forEach(item -> text.append("- ").append(item.getAsJsonObject().get("displayName").getAsString()).append('\n'));

            if (fieldName != null && !fieldName.isBlank()) {
                var fieldCandidates = scope.resolveFieldCandidates(owner, fieldName, descriptor);
                var broadFieldCandidates = (descriptor != null && !descriptor.isBlank())
                        ? scope.resolveFieldCandidates(owner, fieldName, null)
                        : fieldCandidates;
                if (fieldCandidates.isEmpty()) {
                    return ResolutionSupport.unresolvedField(owner, fieldName, descriptor, broadFieldCandidates);
                }
                JsonArray fields = new JsonArray();
                for (ScopedField scopedField : fieldCandidates) {
                    JsonObject item = new JsonObject();
                    item.addProperty("sourcePath", scopedField.sourcePath().toString());
                    item.addProperty("displayName", scopedField.indexedField().displayName());
                    item.addProperty("descriptor", scopedField.indexedField().descriptor());
                    fields.add(item);
                }
                structured.add("fields", fields);
                text.append("\nFields:\n");
                fields.forEach(item -> text.append("- ").append(item.getAsJsonObject().get("displayName").getAsString())
                        .append(" : ").append(item.getAsJsonObject().get("descriptor").getAsString()).append('\n'));
            }

            if (methodName != null && !methodName.isBlank()) {
                var methodsFound = scope.resolveMethodCandidates(owner, methodName, descriptor);
                var broadMethods = (descriptor != null && !descriptor.isBlank())
                        ? scope.resolveMethodCandidates(owner, methodName, null)
                        : methodsFound;
                if (methodsFound.isEmpty()
                        || ((descriptor == null || descriptor.isBlank()) && methodsFound.size() > 1)
                        || (descriptor != null && !descriptor.isBlank() && methodsFound.size() > 1)) {
                    return ResolutionSupport.unresolvedMethod(owner, methodName, descriptor,
                            methodsFound.isEmpty() ? broadMethods : methodsFound);
                }
                JsonArray methods = new JsonArray();
                for (ScopedMethod scopedMethod : methodsFound) {
                    JsonObject item = new JsonObject();
                    item.addProperty("sourcePath", scopedMethod.sourcePath().toString());
                    item.addProperty("displayName", scopedMethod.indexedMethod().displayName());
                    item.addProperty("descriptor", scopedMethod.indexedMethod().ref().descriptor());
                    methods.add(item);
                }
                structured.add("methods", methods);
                text.append("\nMethods:\n");
                methods.forEach(item -> text.append("- ").append(item.getAsJsonObject().get("displayName").getAsString()).append('\n'));
            }
        }

        if (structured.size() == 0) {
            return ResolutionSupport.unresolved("missing_input", "Provide at least className, optionally with fieldName or methodName.");
        }
        structured.addProperty("resolved", true);
        structured.addProperty("indexBackend", "sqlite");
        structured.addProperty("scopeArchiveCount", scope.scopeArchiveCount());
        structured.addProperty("queryMillis", (System.nanoTime() - startedAt) / 1_000_000L);
        structured.addProperty("indexPath", scope.databasePath().toString());
        IndexMetadataSupport.addIndexFailureMetadata(structured, scope);
        return ToolResults.structured(text.toString().trim(), structured);
    }
}
