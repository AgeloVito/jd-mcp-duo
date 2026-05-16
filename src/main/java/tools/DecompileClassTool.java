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
import support.SchemaSupport;
import support.SidecarMetadataSupport;
import support.ToolResults;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

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
        SchemaSupport.addString(properties, "path", "Path to a .class file, directory, or supported archive");
        SchemaSupport.addString(properties, "className", "Class name when path points to a directory or archive (e.g. com.example.Main)");
        SchemaSupport.addString(properties, "engine", "Decompiler engine: auto, jd-core-v1, jd-core-v0, cfr, procyon, fernflower, vineflower, jadx");
        SchemaSupport.addString(properties, "profile", "Decompilation profile: fast, accurate, or debuggable");
        SchemaSupport.addInteger(properties, "releaseVersion", "Target multi-release class version; defaults to the current runtime");
        SchemaSupport.addInteger(properties, "attemptTimeoutMillis", "Per-engine attempt timeout in milliseconds; 0 disables timeout");
        SchemaSupport.addBoolean(properties, "lineNumbers", "Include line number metadata", false);
        SchemaSupport.addString(properties, "renderLineNumbers", "Render visible line numbers in output/source files: decompiled, source, both, or none");
        SchemaSupport.addBoolean(properties, "writeSidecarMetadata", "Write a .meta.json sidecar next to the exported source file", false);
        SchemaSupport.addBoolean(properties, "advancedLookup", "Search sibling archives for dependency resolution; JDK modules are included by default", false);
        SchemaSupport.addStringOrArray(properties, "classpath", "Additional classpath entries");

        JsonObject preferencesProp = new JsonObject();
        preferencesProp.addProperty("type", "object");
        preferencesProp.addProperty("description", "Per-engine raw preferences passed to transformer-api");
        properties.add("preferences", preferencesProp);

        SchemaSupport.addString(properties, "output", "Optional output file path");

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
            throw new IllegalArgumentException("File not found: " + path);
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
                String sourceToWrite = renderLineNumbers != null ? renderedSource
                        : outcome.result() != null ? outcome.result().getDecompiledOutput() : "";
                Files.writeString(outputPath, sourceToWrite);
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
}
