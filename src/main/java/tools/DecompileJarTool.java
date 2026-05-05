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
import support.LineNumberRenderer;
import support.SchemaSupport;
import support.ToolResults;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static decompile.DecompilerEngines.AUTO;
import static decompile.DecompilerSupport.decompile;

public class DecompileJarTool implements MCPTool {
    @Override
    public String getDescription() {
        return "Analyze a supported archive or directory and optionally preview a decompiled class.";
    }

    @Override
    public JsonObject getInputSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        SchemaSupport.addString(properties, "path", "Path to a supported archive or directory");
        SchemaSupport.addString(properties, "className", "Class name to preview when decompile=true and the input contains multiple classes");
        SchemaSupport.addInteger(properties, "limit", "Maximum number of classes to list", 20);
        SchemaSupport.addBoolean(properties, "decompile", "Include a preview of the selected class", false);
        SchemaSupport.addString(properties, "engine", "Decompiler engine for preview");
        SchemaSupport.addString(properties, "profile", "fast, accurate, or debuggable");
        SchemaSupport.addInteger(properties, "releaseVersion", "Target multi-release class version; defaults to the current runtime", Runtime.version().feature());
        SchemaSupport.addInteger(properties, "attemptTimeoutMillis", "Per-engine attempt timeout in milliseconds; 0 disables timeout", (int) decompile.DecompilerOptions.DEFAULT_ATTEMPT_TIMEOUT_MILLIS);
        SchemaSupport.addBoolean(properties, "lineNumbers", "Include line number metadata", false);
        SchemaSupport.addString(properties, "renderLineNumbers", "Render visible line numbers in preview output: decompiled, source, both, or none");
        SchemaSupport.addBoolean(properties, "advancedLookup", "Search sibling archives for dependency resolution; JDK modules are included by default", false);
        SchemaSupport.addStringOrArray(properties, "classpath", "Additional classpath entries");
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
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("File not found: " + path);
        }

        int limit = JsonUtils.getInt(arguments, "limit", 20);
        boolean includePreview = JsonUtils.getBoolean(arguments, "decompile", false);
        String className = JsonUtils.getString(arguments, "className", null);
        DecompilerOptions options = DecompilerOptions.fromArguments(arguments, AUTO);
        String renderLineNumbers = LineNumberRenderer.normalize(JsonUtils.getString(arguments, "renderLineNumbers", null));

        try (InputContainer container = InputContainers.open(path, options.releaseVersion())) {
            List<ClassLocation> classes = container.listClasses(false);
            JsonArray classArray = new JsonArray();
            StringBuilder text = new StringBuilder();
            text.append("Archive analysis for ").append(path).append('\n');
            text.append("Kind: ").append(container.kind()).append('\n');
            text.append("Classes: ").append(classes.size()).append("\n\n");

            for (int i = 0; i < Math.min(limit, classes.size()); i++) {
                ClassLocation location = classes.get(i);
                text.append(String.format("%3d. %s%n", i + 1, location.displayName()));
                JsonObject classJson = new JsonObject();
                classJson.addProperty("internalName", location.internalName());
                classJson.addProperty("displayName", location.displayName());
                classJson.addProperty("entryName", location.entryName());
                classArray.add(classJson);
            }
            if (classes.size() > limit) {
                text.append("\n... ").append(classes.size() - limit).append(" more classes not shown");
            }

            JsonObject structured = new JsonObject();
            structured.addProperty("path", path.toString());
            structured.addProperty("kind", container.kind());
            structured.addProperty("totalClasses", classes.size());
            structured.add("classes", classArray);

            if (includePreview && !classes.isEmpty()) {
                String previewClass = resolvePreviewClass(classes, className);
                DecompilationOutcome preview = decompile(container, previewClass, options);
                String renderedPreview = LineNumberRenderer.render(preview, renderLineNumbers);
                text.append("\n\n--- Preview ---\n").append(renderedPreview);
                structured.addProperty("previewClass", preview.internalName());
                structured.add("preview", DecompilationJson.toJson(preview));
                if (renderLineNumbers != null) {
                    structured.addProperty("renderLineNumbers", renderLineNumbers);
                    structured.addProperty("renderedPreview", renderedPreview);
                }
            }

            return ToolResults.structured(text.toString(), structured);
        }
    }

    private static String resolvePreviewClass(List<ClassLocation> classes, String requestedClass) {
        if (requestedClass != null && !requestedClass.isBlank()) {
            return requestedClass;
        }
        if (classes.size() == 1) {
            return classes.get(0).internalName();
        }
        throw new IllegalArgumentException("className is required for preview when the input contains multiple classes");
    }
}
