package decompile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import jd.core.DecompilationResult;
import jd.core.links.DeclarationData;
import jd.core.links.HyperlinkData;
import jd.core.links.HyperlinkReferenceData;
import jd.core.links.ReferenceData;
import jd.core.links.StringData;

import java.util.Map;
import java.util.NavigableMap;

public final class DecompilationJson {
    private DecompilationJson() {
    }

    public static JsonObject toJson(DecompilationOutcome outcome) {
        JsonObject json = new JsonObject();
        json.addProperty("internalName", outcome.internalName());
        json.addProperty("displayName", outcome.internalName().replace('/', '.'));
        json.addProperty("engineRequested", outcome.engineRequested());
        json.addProperty("engineUsed", outcome.engineUsed());
        json.addProperty("patched", outcome.patched());
        json.addProperty("fallbackUsed", outcome.fallbackUsed());
        json.addProperty("metadataLimited", outcome.metadataLimited());
        json.addProperty("metadataRebuilt", outcome.metadataRebuilt());
        json.addProperty("nativeAndroid", outcome.nativeAndroid());
        json.add("methodPatches", methodPatchesJson(outcome));
        JsonArray attemptedEngines = new JsonArray();
        outcome.attemptedEngines().forEach(attemptedEngines::add);
        json.add("attemptedEngines", attemptedEngines);
        JsonObject engineFailures = new JsonObject();
        outcome.engineFailures().forEach(engineFailures::addProperty);
        json.add("engineFailures", engineFailures);
        json.add("warnings", warningsJson(outcome));

        DecompilationResult result = outcome.result();
        json.addProperty("source", result.getDecompiledOutput());
        boolean includePositionalMetadata = !outcome.metadataLimited() || outcome.metadataRebuilt();
        json.add("strings", stringsJson(result, includePositionalMetadata));
        json.add("declarations", includePositionalMetadata ? declarationsJson(result.getDeclarations()) : new JsonArray());
        json.add("typeDeclarations", includePositionalMetadata ? declarationsJson(result.getTypeDeclarations()) : new JsonArray());
        json.add("references", includePositionalMetadata ? referencesJson(result.getReferences()) : new JsonArray());
        json.add("hyperlinks", includePositionalMetadata ? hyperlinksJson(result.getHyperlinks()) : new JsonArray());
        json.add("lineNumbers", includePositionalMetadata ? lineNumbersJson(result.getLineNumbers()) : new JsonObject());
        json.addProperty("maxLineNumber", includePositionalMetadata ? result.getMaxLineNumber() : 0);
        return json;
    }

    public static void addOutcomeSummary(JsonObject target, DecompilationOutcome outcome) {
        target.addProperty("engineUsed", outcome.engineUsed());
        target.addProperty("patched", outcome.patched());
        target.addProperty("fallbackUsed", outcome.fallbackUsed());
        target.addProperty("metadataLimited", outcome.metadataLimited());
        target.addProperty("metadataRebuilt", outcome.metadataRebuilt());
        target.addProperty("methodPatchCount", outcome.methodPatches().size());
        target.add("methodPatches", methodPatchesJson(outcome));
        target.add("warnings", warningsJson(outcome));
        JsonArray attempted = new JsonArray();
        outcome.attemptedEngines().forEach(attempted::add);
        target.add("attemptedEngines", attempted);
        JsonObject engineFailures = new JsonObject();
        outcome.engineFailures().forEach(engineFailures::addProperty);
        target.add("engineFailures", engineFailures);
    }

    public static JsonArray methodPatchesJson(DecompilationOutcome outcome) {
        JsonArray array = new JsonArray();
        for (JdtMethodPatcher.MethodPatch patch : outcome.methodPatches()) {
            JsonObject item = new JsonObject();
            item.addProperty("bindingKey", patch.bindingKey());
            item.addProperty("v1StartLine", patch.v1StartLine());
            item.addProperty("v1EndLine", patch.v1EndLine());
            item.addProperty("v0StartLine", patch.v0StartLine());
            item.addProperty("v0EndLine", patch.v0EndLine());
            array.add(item);
        }
        return array;
    }

    public static JsonArray warningsJson(DecompilationOutcome outcome) {
        JsonArray warnings = new JsonArray();
        outcome.warnings().forEach(warnings::add);
        return warnings;
    }

    private static JsonArray stringsJson(DecompilationResult result, boolean includePositions) {
        JsonArray strings = new JsonArray();
        for (Object item : result.getStrings()) {
            if (item instanceof StringData stringData) {
                JsonObject json = new JsonObject();
                json.addProperty("owner", stringData.owner());
                json.addProperty("text", stringData.text());
                if (includePositions) {
                    json.addProperty("startPosition", stringData.startPosition());
                }
                strings.add(json);
            }
        }
        return strings;
    }

    private static JsonArray declarationsJson(Map<?, ?> declarations) {
        JsonArray array = new JsonArray();
        for (Map.Entry<?, ?> entry : declarations.entrySet()) {
            if (entry.getValue() instanceof DeclarationData declaration) {
                JsonObject json = new JsonObject();
                json.addProperty("key", String.valueOf(entry.getKey()));
                json.addProperty("typeName", declaration.getTypeName());
                json.addProperty("name", declaration.getName());
                json.addProperty("startPosition", declaration.getStartPosition());
                json.addProperty("endPosition", declaration.getEndPosition());
                json.addProperty("isType", declaration.isAType());
                json.addProperty("isField", declaration.isAField());
                json.addProperty("isMethod", declaration.isAMethod());
                json.addProperty("isConstructor", declaration.isAConstructor());
                array.add(json);
            }
        }
        return array;
    }

    private static JsonArray referencesJson(Iterable<?> references) {
        JsonArray array = new JsonArray();
        for (Object item : references) {
            if (item instanceof ReferenceData reference) {
                JsonObject json = new JsonObject();
                json.addProperty("typeName", reference.getTypeName());
                json.addProperty("owner", reference.getOwner());
                json.addProperty("name", reference.getName());
                json.addProperty("descriptor", reference.getDescriptor());
                json.addProperty("enabled", reference.isEnabled());
                json.addProperty("isType", reference.isAType());
                json.addProperty("isField", reference.isAField());
                json.addProperty("isMethod", reference.isAMethod());
                json.addProperty("isConstructor", reference.isAConstructor());
                array.add(json);
            }
        }
        return array;
    }

    private static JsonArray hyperlinksJson(Map<?, ?> hyperlinks) {
        JsonArray array = new JsonArray();
        for (Map.Entry<?, ?> entry : hyperlinks.entrySet()) {
            if (entry.getValue() instanceof HyperlinkData hyperlink) {
                JsonObject json = new JsonObject();
                json.addProperty("offset", String.valueOf(entry.getKey()));
                json.addProperty("startPosition", hyperlink.getStartPosition());
                json.addProperty("endPosition", hyperlink.getEndPosition());
                json.addProperty("enabled", hyperlink.isEnabled());
                if (hyperlink instanceof HyperlinkReferenceData referenceData) {
                    json.add("reference", referencesJson(java.util.List.of(referenceData.getReference())).get(0));
                }
                array.add(json);
            }
        }
        return array;
    }

    private static JsonObject lineNumbersJson(Map<?, ?> lineNumbers) {
        JsonObject json = new JsonObject();
        for (Map.Entry<?, ?> entry : lineNumbers.entrySet()) {
            json.addProperty(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        return json;
    }
}
