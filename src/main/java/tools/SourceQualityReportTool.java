package tools;

import archive.ClassLocation;
import archive.InputContainer;
import archive.InputContainers;
import decompile.DecompilationJson;
import decompile.DecompilationOutcome;
import decompile.DecompilerOptions;
import model.MCPTool;
import model.ToolResult;
import support.JsonUtils;
import support.ProgressReporter;
import support.SchemaSupport;
import support.ToolResults;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static decompile.DecompilerEngines.AUTO;

public class SourceQualityReportTool implements MCPTool {
    @Override
    public String getDescription() {
        return "Report decompilation quality across all classes in an archive or directory.";
    }

    @Override
    public JsonObject getInputSchema() {
        JsonObject schema = SchemaSupport.objectSchema();
        JsonObject properties = SchemaSupport.properties(schema);
        SchemaSupport.addString(properties, "path", "Input path");
        SchemaSupport.addString(properties, "engine", "Decompiler engine");
        SchemaSupport.addInteger(properties, "releaseVersion", "Target multi-release class version; defaults to the current runtime", Runtime.version().feature());
        SchemaSupport.addInteger(properties, "attemptTimeoutMillis", "Per-engine attempt timeout in milliseconds; 0 disables timeout", (int) decompile.DecompilerOptions.DEFAULT_ATTEMPT_TIMEOUT_MILLIS);
        SchemaSupport.addBoolean(properties, "lineNumbers", "Include line-number metadata", false);
        SchemaSupport.addBoolean(properties, "advancedLookup", "Search sibling archives for dependency resolution; JDK modules are included by default", false);
        SchemaSupport.addStringOrArray(properties, "classpath", "Additional classpath entries");
        SchemaSupport.addInteger(properties, "classLimit", "Maximum classes to analyze", 100);
        JsonObject prefs = new JsonObject();
        prefs.addProperty("type", "object");
        prefs.addProperty("description", "Per-engine raw preferences passed to transformer-api");
        properties.add("preferences", prefs);
        SchemaSupport.require(schema, "path");
        return schema;
    }

    @Override
    public ToolResult execute(JsonObject arguments) throws Exception {
        return execute(arguments, new ProgressReporter(null, "source_quality_report"));
    }

    @Override
    public ToolResult execute(JsonObject arguments, ProgressReporter reporter) throws Exception {
        reporter.report(0, 0);
        Path path = JsonUtils.getRequiredPath(arguments, "path");
        int classLimit = JsonUtils.getInt(arguments, "classLimit", 100);
        DecompilerOptions options = DecompilerOptions.fromArguments(arguments, AUTO);

        try (InputContainer container = InputContainers.open(path, options.releaseVersion());
             decompile.DecompilerSession session = decompile.DecompilerSession.open(container, options)) {
            List<ClassLocation> classes = container.listClasses(false);
            if (classLimit > 0 && classes.size() > classLimit) {
                classes = classes.subList(0, classLimit);
            }

            int success = 0;
            int patched = 0;
            int fallback = 0;
            int nativeAndroid = 0;
            int metadataLimited = 0;
            int failed = 0;
            Map<String, Integer> engineCounts = new HashMap<>();
            Map<String, Integer> failureCategories = new HashMap<>();
            JsonArray failures = new JsonArray();
            JsonArray classResults = new JsonArray();

            int total = classes.size();
            int idx = 0;
            for (ClassLocation location : classes) {
                JsonObject classResult = new JsonObject();
                classResult.addProperty("className", location.displayName());
                classResult.addProperty("internalName", location.internalName());
                try {
                    DecompilationOutcome outcome = session.decompile(location.internalName());
                    success++;
                    if (outcome.patched()) {
                        patched++;
                    }
                    if (outcome.fallbackUsed()) {
                        fallback++;
                    }
                    if (outcome.nativeAndroid()) {
                        nativeAndroid++;
                    }
                    if (outcome.metadataLimited()) {
                        metadataLimited++;
                    }
                    engineCounts.merge(outcome.engineUsed(), 1, Integer::sum);
                    classResult.addProperty("success", true);
                    classResult.addProperty("nativeAndroid", outcome.nativeAndroid());
                    DecompilationJson.addOutcomeSummary(classResult, outcome);
                } catch (Exception e) {
                    failed++;
                    JsonObject failure = new JsonObject();
                    failure.addProperty("className", location.displayName());
                    failure.addProperty("error", e.getMessage());
                    String failureCategory = categorizeFailure(e.getMessage());
                    failure.addProperty("category", failureCategory);
                    failureCategories.merge(failureCategory, 1, Integer::sum);
                    failures.add(failure);
                    classResult.addProperty("success", false);
                    classResult.addProperty("error", e.getMessage());
                    classResult.addProperty("failureCategory", failureCategory);
                }
                classResults.add(classResult);
                reporter.tick(++idx, total, location.displayName());
            }
            reporter.done();

            JsonObject structured = new JsonObject();
            structured.addProperty("total", classes.size());
            structured.addProperty("success", success);
            structured.addProperty("patched", patched);
            structured.addProperty("fallback", fallback);
            structured.addProperty("nativeAndroid", nativeAndroid);
            structured.addProperty("metadataLimited", metadataLimited);
            structured.addProperty("failed", failed);
            JsonObject engines = new JsonObject();
            engineCounts.forEach(engines::addProperty);
            structured.add("engines", engines);
            JsonObject failureCategoriesJson = new JsonObject();
            failureCategories.forEach(failureCategoriesJson::addProperty);
            structured.add("failureCategories", failureCategoriesJson);
            structured.add("failures", failures);
            structured.add("classResults", classResults);

            String text = "Quality report\n"
                    + "Total: " + classes.size() + "\n"
                    + "Success: " + success + "\n"
                    + "Patched: " + patched + "\n"
                    + "Fallback: " + fallback + "\n"
                    + "Native Android: " + nativeAndroid + "\n"
                    + "Metadata limited: " + metadataLimited + "\n"
                    + "Failed: " + failed;
            return ToolResults.structured(text, structured);
        }
    }

    private static String categorizeFailure(String message) {
        if (message == null || message.isBlank()) {
            return "unknown";
        }
        String normalized = message.toLowerCase();
        if (normalized.contains("class not found")) {
            return "class_not_found";
        }
        if (normalized.contains("empty output") || normalized.contains("produced empty output")) {
            return "empty_output";
        }
        if (normalized.contains("unsupported")) {
            return "unsupported";
        }
        if (normalized.contains("jadx") || normalized.contains("android native") || normalized.contains("dex2jar")) {
            return "android_native";
        }
        if (normalized.contains("patch")) {
            return "patch";
        }
        return "other";
    }
}
