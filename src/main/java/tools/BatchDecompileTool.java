package tools;

import archive.ClassLocation;
import archive.InputContainer;
import archive.InputContainers;
import decompile.DecompilationJson;
import decompile.DecompilationSummary;
import decompile.DecompilerOptions;
import model.MCPTool;
import model.ToolResult;
import support.JsonUtils;
import support.LineNumberRenderer;
import support.SidecarMetadataSupport;
import support.ToolResults;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static decompile.DecompilerEngines.AUTO;

public class BatchDecompileTool implements MCPTool {
    @Override
    public String getDescription() {
        return "Batch decompile classes from a directory root.";
    }

    @Override
    public JsonObject getInputSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        addStringProperty(properties, "path", "Directory containing class files");
        addStringProperty(properties, "engine", "Decompiler engine");
        addStringProperty(properties, "profile", "fast, accurate, or debuggable");
        addIntProperty(properties, "releaseVersion", "Target multi-release class version; defaults to the current runtime", Runtime.version().feature());
        addIntProperty(properties, "attemptTimeoutMillis", "Per-engine attempt timeout in milliseconds; 0 disables timeout", (int) decompile.DecompilerOptions.DEFAULT_ATTEMPT_TIMEOUT_MILLIS);
        addBooleanProperty(properties, "lineNumbers", "Include line number metadata", false);
        addStringProperty(properties, "renderLineNumbers", "Render visible line numbers in output/source fields: decompiled, source, both, or none");
        addBooleanProperty(properties, "writeSidecarMetadata", "Write .meta.json sidecars next to exported sources", false);
        addBooleanProperty(properties, "advancedLookup", "Search sibling archives for dependency resolution; JDK modules are included by default", false);
        addStringOrArrayProperty(properties, "classpath", "Additional classpath entries");
        addIntProperty(properties, "limit", "Maximum classes to decompile", 0);
        addBooleanProperty(properties, "summaryOnly", "Only return summary text", false);
        addStringProperty(properties, "outputDir", "Optional output directory");
        JsonObject preferences = new JsonObject();
        preferences.addProperty("type", "object");
        properties.add("preferences", preferences);
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("path");
        schema.add("required", required);
        return schema;
    }

    @Override
    public ToolResult execute(JsonObject arguments) throws Exception {
        Path path = JsonUtils.getRequiredPath(arguments, "path");
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("Path must be a directory: " + path);
        }

        DecompilerOptions options = DecompilerOptions.fromArguments(arguments, AUTO);
        int limit = JsonUtils.getInt(arguments, "limit", 0);
        boolean summaryOnly = JsonUtils.getBoolean(arguments, "summaryOnly", false);
        String outputDir = JsonUtils.getString(arguments, "outputDir", null);
        String renderLineNumbers = LineNumberRenderer.normalize(JsonUtils.getString(arguments, "renderLineNumbers", null));
        boolean writeSidecarMetadata = JsonUtils.getBoolean(arguments, "writeSidecarMetadata", false);

        try (InputContainer container = InputContainers.open(path, options.releaseVersion());
             decompile.DecompilerSession session = decompile.DecompilerSession.open(container, options)) {
            List<ClassLocation> classes = container.listClasses(false);
            if (limit > 0 && classes.size() > limit) {
                classes = classes.subList(0, limit);
            }

            JsonArray results = new JsonArray();
            StringBuilder text = new StringBuilder();
            text.append("Batch decompile from ").append(path).append('\n');

            for (ClassLocation location : classes) {
                JsonObject item = new JsonObject();
                item.addProperty("className", location.displayName());
                try {
                    var outcome = session.decompile(location.internalName());
                    item.addProperty("success", true);
                    item.addProperty("engineUsed", outcome.engineUsed());
                    item.addProperty("patched", outcome.patched());
                    item.addProperty("fallbackUsed", outcome.fallbackUsed());
                    item.addProperty("metadataLimited", outcome.metadataLimited());
                    item.addProperty("metadataRebuilt", outcome.metadataRebuilt());
                    item.addProperty("methodPatchCount", outcome.methodPatches().size());
                    item.add("methodPatches", DecompilationJson.methodPatchesJson(outcome));
                    item.add("warnings", DecompilationJson.warningsJson(outcome));
                    JsonArray attempted = new JsonArray();
                    outcome.attemptedEngines().forEach(attempted::add);
                    item.add("attemptedEngines", attempted);
                    JsonObject engineFailures = new JsonObject();
                    outcome.engineFailures().forEach(engineFailures::addProperty);
                    item.add("engineFailures", engineFailures);
                    item.addProperty("lines", outcome.result().getDecompiledOutput().lines().count());
                    if (!summaryOnly) {
                        item.addProperty("source", outcome.result().getDecompiledOutput());
                        if (renderLineNumbers != null) {
                            item.addProperty("renderedSource", LineNumberRenderer.render(outcome, renderLineNumbers));
                        }
                    }
                    if (outputDir != null && !outputDir.isBlank()) {
                        Path outputPath = Path.of(outputDir).resolve(location.internalName() + ".java");
                        Files.createDirectories(outputPath.getParent());
                        Files.writeString(outputPath, LineNumberRenderer.render(outcome, renderLineNumbers));
                        if (writeSidecarMetadata) {
                            SidecarMetadataSupport.writeFile(outputPath, DecompilationJson.toJson(outcome));
                            item.addProperty("savedMetadataTo", SidecarMetadataSupport.sidecarPath(outputPath).toString());
                        }
                        item.addProperty("savedTo", outputPath.toString());
                    }
                    text.append("✅ ").append(location.displayName())
                            .append(" [").append(outcome.engineUsed()).append(']');
                    String status = DecompilationSummary.inlineStatus(outcome);
                    if (!status.isBlank()) {
                        text.append(" (").append(status).append(')');
                    }
                    text.append('\n');
                } catch (Exception e) {
                    item.addProperty("success", false);
                    item.addProperty("error", e.getMessage());
                    text.append("❌ ").append(location.displayName()).append(" - ").append(e.getMessage()).append('\n');
                }
                results.add(item);
            }

            JsonObject structured = new JsonObject();
            structured.addProperty("path", path.toString());
            structured.add("results", results);
            return ToolResults.structured(text.toString().trim(), structured);
        }
    }

    private static void addStringProperty(JsonObject properties, String name, String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "string");
        property.addProperty("description", description);
        properties.add(name, property);
    }

    private static void addBooleanProperty(JsonObject properties, String name, String description, boolean defaultValue) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "boolean");
        property.addProperty("description", description);
        property.addProperty("default", defaultValue);
        properties.add(name, property);
    }

    private static void addIntProperty(JsonObject properties, String name, String description, int defaultValue) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "integer");
        property.addProperty("description", description);
        property.addProperty("default", defaultValue);
        properties.add(name, property);
    }

    private static void addStringOrArrayProperty(JsonObject properties, String name, String description) {
        JsonObject property = new JsonObject();
        JsonArray types = new JsonArray();
        types.add("string");
        types.add("array");
        property.add("type", types);
        property.addProperty("description", description);
        properties.add(name, property);
    }
}
