package tools;

import archive.ClassLocation;
import archive.InputContainer;
import archive.InputContainers;
import decompile.DecompilationJson;
import decompile.DecompilationOutcome;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static decompile.DecompilerEngines.AUTO;
import static decompile.DecompilerSupport.decompile;

public class DecompileClassTool implements MCPTool {
    @Override
    public String getDescription() {
        return "Decompile a single class from a .class file, directory, or archive using transformer-api with structured metadata.";
    }

    @Override
    public JsonObject getInputSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject properties = new JsonObject();
        addStringProperty(properties, "path", "Path to a .class file, directory, or supported archive");
        addStringProperty(properties, "className", "Class name when path points to a directory or archive (e.g. com.example.Main)");
        addStringProperty(properties, "engine", "Decompiler engine: auto, jd-core-duo, jd-core-v1, jd-core-v0, cfr, procyon, fernflower, vineflower, jadx");
        addStringProperty(properties, "profile", "Decompilation profile: fast, accurate, or debuggable");
        addIntegerProperty(properties, "releaseVersion", "Target multi-release class version; defaults to the current runtime");
        addIntegerProperty(properties, "attemptTimeoutMillis", "Per-engine attempt timeout in milliseconds; 0 disables timeout");
        addBooleanProperty(properties, "lineNumbers", "Include line number metadata", false);
        addStringProperty(properties, "renderLineNumbers", "Render visible line numbers in output/source files: decompiled, source, both, or none");
        addBooleanProperty(properties, "writeSidecarMetadata", "Write a .meta.json sidecar next to the exported source file", false);
        addBooleanProperty(properties, "advancedLookup", "Search sibling archives for dependency resolution; JDK modules are included by default", false);
        addStringOrArrayProperty(properties, "classpath", "Additional classpath entries");

        JsonObject preferencesProp = new JsonObject();
        preferencesProp.addProperty("type", "object");
        preferencesProp.addProperty("description", "Per-engine raw preferences passed to transformer-api");
        properties.add("preferences", preferencesProp);

        addStringProperty(properties, "output", "Optional output file path");

        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("path");
        schema.add("required", required);
        return schema;
    }

    @Override
    public ToolResult execute(JsonObject arguments) throws Exception {
        Path path = JsonUtils.getRequiredPath(arguments, "path");
        if (!Files.exists(path)) {
            throw new IOException("File not found: " + path);
        }

        DecompilerOptions options = DecompilerOptions.fromArguments(arguments, AUTO);
        String requestedClass = JsonUtils.getString(arguments, "className", null);
        String output = JsonUtils.getString(arguments, "output", null);
        String renderLineNumbers = LineNumberRenderer.normalize(JsonUtils.getString(arguments, "renderLineNumbers", null));
        boolean writeSidecarMetadata = JsonUtils.getBoolean(arguments, "writeSidecarMetadata", false);

        try (InputContainer container = InputContainers.open(path, options.releaseVersion())) {
            String className = resolveRequestedClass(container, requestedClass);
            DecompilationOutcome outcome = decompile(container, className, options);
            JsonObject structured = DecompilationJson.toJson(outcome);
            String renderedSource = LineNumberRenderer.render(outcome, renderLineNumbers);
            if (renderLineNumbers != null) {
                structured.addProperty("renderLineNumbers", renderLineNumbers);
                structured.addProperty("renderedSource", renderedSource);
            }

            if (output != null && !output.isBlank()) {
                Path outputPath = Path.of(output).toAbsolutePath().normalize();
                if (outputPath.getParent() != null) {
                    Files.createDirectories(outputPath.getParent());
                }
                Files.writeString(outputPath, renderLineNumbers == null ? outcome.result().getDecompiledOutput() : renderedSource);
                if (writeSidecarMetadata) {
                    SidecarMetadataSupport.writeFile(outputPath, structured);
                }
                JsonObject saved = structured.deepCopy();
                saved.addProperty("savedTo", outputPath.toString());
                if (writeSidecarMetadata) {
                    saved.addProperty("savedMetadataTo", SidecarMetadataSupport.sidecarPath(outputPath).toString());
                }
                String message = "Decompiled " + outcome.internalName().replace('/', '.') + " to " + outputPath;
                String details = DecompilationSummary.detailBlock(outcome);
                if (!details.isBlank()) {
                    message += "\n" + details;
                }
                return ToolResults.structured(message, saved);
            }

            return ToolResults.structured(renderLineNumbers == null ? outcome.result().getDecompiledOutput() : renderedSource, structured);
        }
    }

    private static String resolveRequestedClass(InputContainer container, String requestedClass) {
        if (requestedClass != null && !requestedClass.isBlank()) {
            return requestedClass;
        }

        ClassLocation defaultClass = container.defaultClass();
        if (defaultClass != null) {
            return defaultClass.internalName();
        }

        throw new IllegalArgumentException("className is required when the input contains multiple classes");
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

    private static void addIntegerProperty(JsonObject properties, String name, String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "integer");
        property.addProperty("description", description);
        properties.add(name, property);
    }

    private static void addStringOrArrayProperty(JsonObject properties, String name, String description) {
        JsonObject property = new JsonObject();
        property.addProperty("description", description);
        JsonArray types = new JsonArray();
        types.add("string");
        types.add("array");
        property.add("type", types);
        properties.add(name, property);
    }
}
