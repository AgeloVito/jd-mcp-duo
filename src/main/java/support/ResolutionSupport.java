package support;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import index.MethodRef;
import index.ScopedField;
import index.ScopedMethod;
import model.ToolResult;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ResolutionSupport {
    private ResolutionSupport() {
    }

    public static ToolResult unresolved(String reason, String hint) {
        JsonObject structured = new JsonObject();
        structured.addProperty("resolved", false);
        structured.addProperty("unresolvedReason", reason);
        if (hint != null && !hint.isBlank()) {
            structured.addProperty("unresolvedHint", hint);
        }
        return ToolResults.error(hint == null || hint.isBlank() ? reason : hint, structured);
    }

    public static ToolResult unresolvedMethod(String owner, String methodName, String descriptor, List<ScopedMethod> candidates) {
        JsonObject structured = new JsonObject();
        structured.addProperty("resolved", false);
        structured.addProperty("owner", owner);
        structured.addProperty("methodName", methodName);
        if (descriptor != null) {
            structured.addProperty("descriptor", descriptor);
        }

        JsonArray candidateDescriptors = new JsonArray();
        JsonArray candidateMethods = new JsonArray();
        for (ScopedMethod candidate : candidates) {
            MethodRef ref = candidate.indexedMethod().ref();
            candidateDescriptors.add(ref.descriptor());
            JsonObject item = new JsonObject();
            item.addProperty("sourcePath", candidate.sourcePath().toString());
            item.addProperty("owner", ref.owner());
            item.addProperty("displayOwner", ref.owner().replace('/', '.'));
            item.addProperty("name", ref.name());
            item.addProperty("descriptor", ref.descriptor());
            item.addProperty("displayName", ref.displayName());
            candidateMethods.add(item);
        }
        structured.add("candidateDescriptors", candidateDescriptors);
        structured.add("candidateMethods", candidateMethods);

        Set<String> distinctSourcePaths = new LinkedHashSet<>();
        for (ScopedMethod candidate : candidates) {
            distinctSourcePaths.add(candidate.sourcePath().toString());
        }

        if (candidates.isEmpty()) {
            structured.addProperty("unresolvedReason", descriptor == null || descriptor.isBlank() ? "method_not_found" : "descriptor_mismatch");
            structured.addProperty("unresolvedHint", descriptor == null || descriptor.isBlank()
                    ? "No matching method found. Check className/methodName or provide the exact JVM descriptor for overloaded methods."
                    : "Descriptor did not match any known overload. Use one of the candidateDescriptors values.");
        } else if (descriptor == null || descriptor.isBlank()) {
            structured.addProperty("unresolvedReason", "overload_ambiguous");
            structured.addProperty("unresolvedHint", "Multiple overloads found across " + distinctSourcePaths.size() + " archive(s). Provide the exact JVM descriptor, e.g. ()V or (Ljava/lang/String;)I.");
        } else {
            structured.addProperty("unresolvedReason", "descriptor_mismatch");
            structured.addProperty("unresolvedHint", "Descriptor did not match any known overload. Use one of the candidateDescriptors values.");
        }
        addSourcePaths(structured, distinctSourcePaths);

        return ToolResults.error(structured.get("unresolvedHint").getAsString(), structured);
    }

    public static ToolResult unresolvedMethodSourceAmbiguous(String owner, String methodName, String descriptor, List<ScopedMethod> candidates) {
        JsonObject structured = new JsonObject();
        structured.addProperty("resolved", false);
        structured.addProperty("owner", owner);
        structured.addProperty("methodName", methodName);
        if (descriptor != null) {
            structured.addProperty("descriptor", descriptor);
        }

        JsonArray candidateMethods = new JsonArray();
        for (ScopedMethod candidate : candidates) {
            MethodRef ref = candidate.indexedMethod().ref();
            JsonObject item = new JsonObject();
            item.addProperty("sourcePath", candidate.sourcePath().toString());
            item.addProperty("owner", ref.owner());
            item.addProperty("displayOwner", ref.owner().replace('/', '.'));
            item.addProperty("name", ref.name());
            item.addProperty("descriptor", ref.descriptor());
            item.addProperty("displayName", ref.displayName());
            candidateMethods.add(item);
        }
        Set<String> paths = new LinkedHashSet<>();
        for (ScopedMethod candidate : candidates) {
            paths.add(candidate.sourcePath().toString());
        }
        structured.add("candidateMethods", candidateMethods);
        addSourcePaths(structured, paths);
        structured.addProperty("unresolvedReason", "source_ambiguous");
        structured.addProperty("unresolvedHint", "The exact method exists in " + paths.size() + " scoped archive(s). Narrow scopePath or analyze one archive at a time. Archives: " + paths);
        return ToolResults.error(structured.get("unresolvedHint").getAsString(), structured);
    }

    public static ToolResult unresolvedField(String owner, String fieldName, String descriptor, List<ScopedField> candidates) {
        JsonObject structured = new JsonObject();
        structured.addProperty("resolved", false);
        structured.addProperty("owner", owner);
        structured.addProperty("fieldName", fieldName);
        if (descriptor != null) {
            structured.addProperty("descriptor", descriptor);
        }

        JsonArray candidateDescriptors = new JsonArray();
        JsonArray candidateFields = new JsonArray();
        for (ScopedField candidate : candidates) {
            candidateDescriptors.add(candidate.indexedField().descriptor());
            JsonObject item = new JsonObject();
            item.addProperty("sourcePath", candidate.sourcePath().toString());
            item.addProperty("owner", candidate.indexedField().owner());
            item.addProperty("displayOwner", candidate.indexedField().owner().replace('/', '.'));
            item.addProperty("name", candidate.indexedField().name());
            item.addProperty("descriptor", candidate.indexedField().descriptor());
            item.addProperty("displayName", candidate.indexedField().displayName());
            candidateFields.add(item);
        }
        structured.add("candidateDescriptors", candidateDescriptors);
        structured.add("candidateFields", candidateFields);

        if (candidates.isEmpty()) {
            structured.addProperty("unresolvedReason", descriptor == null || descriptor.isBlank() ? "field_not_found" : "descriptor_mismatch");
            structured.addProperty("unresolvedHint", descriptor == null || descriptor.isBlank()
                    ? "No matching field found. Check className/fieldName or provide the exact JVM descriptor."
                    : "Descriptor did not match any known field. Use one of the candidateDescriptors values.");
        } else if (descriptor == null || descriptor.isBlank()) {
            structured.addProperty("unresolvedReason", "field_ambiguous");
            structured.addProperty("unresolvedHint", "Multiple matching fields found. Provide the exact JVM descriptor.");
        } else {
            structured.addProperty("unresolvedReason", "descriptor_mismatch");
            structured.addProperty("unresolvedHint", "Descriptor did not match any known field. Use one of the candidateDescriptors values.");
        }

        return ToolResults.error(structured.get("unresolvedHint").getAsString(), structured);
    }

    public static ToolResult unresolvedFieldSourceAmbiguous(String owner, String fieldName, String descriptor, List<ScopedField> candidates) {
        JsonObject structured = new JsonObject();
        structured.addProperty("resolved", false);
        structured.addProperty("owner", owner);
        structured.addProperty("fieldName", fieldName);
        if (descriptor != null) {
            structured.addProperty("descriptor", descriptor);
        }

        JsonArray candidateFields = new JsonArray();
        for (ScopedField candidate : candidates) {
            JsonObject item = new JsonObject();
            item.addProperty("sourcePath", candidate.sourcePath().toString());
            item.addProperty("owner", candidate.indexedField().owner());
            item.addProperty("displayOwner", candidate.indexedField().owner().replace('/', '.'));
            item.addProperty("name", candidate.indexedField().name());
            item.addProperty("descriptor", candidate.indexedField().descriptor());
            item.addProperty("displayName", candidate.indexedField().displayName());
            candidateFields.add(item);
        }
        structured.add("candidateFields", candidateFields);
        structured.addProperty("unresolvedReason", "source_ambiguous");
        structured.addProperty("unresolvedHint", "The exact field exists in multiple scoped archives. Narrow scopePath or analyze one archive at a time.");
        return ToolResults.error(structured.get("unresolvedHint").getAsString(), structured);
    }

    private static void addSourcePaths(JsonObject structured, Set<String> paths) {
        JsonArray sources = new JsonArray();
        for (String path : paths) {
            sources.add(path);
        }
        structured.add("sourcePaths", sources);
    }
}
