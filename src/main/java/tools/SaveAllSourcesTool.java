package tools;

import archive.ClassLocation;
import archive.InputContainer;
import archive.InputContainers;
import archive.ResourceEntry;
import decompile.DecompilationOutcome;
import decompile.DecompilerOptions;
import decompile.DecompilationJson;
import model.MCPTool;
import model.ToolResult;
import support.JsonUtils;
import support.LineNumberRenderer;
import support.ProgressReporter;
import support.SchemaSupport;
import support.SidecarMetadataSupport;
import support.ToolResults;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static decompile.DecompilerEngines.AUTO;

public class SaveAllSourcesTool implements MCPTool {
    @Override
    public String getDescription() {
        return "Decompile all classes from an input archive or directory and save them to a directory or sources jar.";
    }

    @Override
    public JsonObject getInputSchema() {
        JsonObject schema = SchemaSupport.objectSchema();
        JsonObject properties = SchemaSupport.properties(schema);
        SchemaSupport.addString(properties, "path", "Input path");
        SchemaSupport.addString(properties, "output", "Output directory or sources jar path");
        SchemaSupport.addString(properties, "format", "directory or sources-jar");
        SchemaSupport.addString(properties, "engine", "Decompiler engine");
        SchemaSupport.addString(properties, "profile", "fast, accurate, or debuggable");
        SchemaSupport.addInteger(properties, "releaseVersion", "Target multi-release class version; defaults to the current runtime", Runtime.version().feature());
        SchemaSupport.addInteger(properties, "attemptTimeoutMillis", "Per-engine attempt timeout in milliseconds; 0 disables timeout", (int) decompile.DecompilerOptions.DEFAULT_ATTEMPT_TIMEOUT_MILLIS);
        SchemaSupport.addBoolean(properties, "lineNumbers", "Include line-number metadata", false);
        SchemaSupport.addString(properties, "renderLineNumbers", "Render visible line numbers in exported files: decompiled, source, both, or none");
        SchemaSupport.addBoolean(properties, "writeSidecarMetadata", "Write .meta.json sidecars next to exported sources", false);
        SchemaSupport.addBoolean(properties, "advancedLookup", "Search sibling archives for dependency resolution; JDK modules are included by default", false);
        SchemaSupport.addStringOrArray(properties, "classpath", "Additional classpath entries");
        SchemaSupport.require(schema, "path");
        SchemaSupport.require(schema, "output");
        return schema;
    }

    @Override
    public ToolResult execute(JsonObject arguments) throws Exception {
        return execute(arguments, new ProgressReporter(null, "save_all_sources"));
    }

    @Override
    public ToolResult execute(JsonObject arguments, ProgressReporter reporter) throws Exception {
        reporter.report(0, 0);
        Path input = JsonUtils.getRequiredPath(arguments, "path");
        Path output = JsonUtils.getRequiredPath(arguments, "output");
        String format = JsonUtils.getString(arguments, "format", output.toString().endsWith(".jar") ? "sources-jar" : "directory");
        DecompilerOptions options = DecompilerOptions.fromArguments(arguments, AUTO);
        String renderLineNumbers = LineNumberRenderer.normalize(JsonUtils.getString(arguments, "renderLineNumbers", null));
        boolean writeSidecarMetadata = JsonUtils.getBoolean(arguments, "writeSidecarMetadata", false);

        JsonArray saved = new JsonArray();
        JsonArray failures = new JsonArray();
        JsonArray resourcesCopied = new JsonArray();
        try (InputContainer container = InputContainers.open(input, options.releaseVersion())) {
            try (decompile.DecompilerSession session = decompile.DecompilerSession.open(container, options)) {
            if ("sources-jar".equals(format)) {
                if (output.getParent() != null) {
                    Files.createDirectories(output.getParent());
                }
                try (JarOutputStream jarOutputStream = new JarOutputStream(Files.newOutputStream(output))) {
                    var classes = container.listClasses(false);
                    int total = classes.size();
                    int idx = 0;
                    for (ClassLocation location : classes) {
                        try {
                            DecompilationOutcome outcome = session.decompile(location.internalName());
                            String sourceEntryName = location.entryName().replaceAll("\\.class$", ".java");
                            jarOutputStream.putNextEntry(new JarEntry(sourceEntryName));
                            jarOutputStream.write(LineNumberRenderer.render(outcome, renderLineNumbers).getBytes(StandardCharsets.UTF_8));
                            jarOutputStream.closeEntry();
                            if (writeSidecarMetadata) {
                                SidecarMetadataSupport.writeJarEntry(jarOutputStream, sourceEntryName, DecompilationJson.toJson(outcome));
                            }
                            JsonObject savedEntry = new JsonObject();
                            savedEntry.addProperty("className", location.displayName());
                            DecompilationJson.addOutcomeSummary(savedEntry, outcome);
                            saved.add(savedEntry);
                        } catch (Exception e) {
                            failures.add(failure(location, e));
                        }
                        reporter.report(++idx, total);
                    }
                    for (ResourceEntry resource : container.listResources()) {
                        try {
                            byte[] bytes = container.loadResourceBytes(resource.entryName());
                            if (bytes == null) continue;
                            jarOutputStream.putNextEntry(new JarEntry(resource.entryName()));
                            jarOutputStream.write(bytes);
                            jarOutputStream.closeEntry();
                            JsonObject copied = new JsonObject();
                            copied.addProperty("entryName", resource.entryName());
                            copied.addProperty("size", bytes.length);
                            resourcesCopied.add(copied);
                        } catch (Exception e) {
                            JsonObject fail = new JsonObject();
                            fail.addProperty("entryName", resource.entryName());
                            fail.addProperty("error", e.getMessage());
                            resourcesCopied.add(fail);
                        }
                    }
                }
            } else {
                Files.createDirectories(output);
                var classes = container.listClasses(false);
                int total = classes.size();
                int idx = 0;
                for (ClassLocation location : classes) {
                    try {
                        DecompilationOutcome outcome = session.decompile(location.internalName());
                        Path javaFile = output.resolve(location.entryName().replaceAll("\\.class$", ".java"));
                        Files.createDirectories(javaFile.getParent());
                        Files.writeString(javaFile, LineNumberRenderer.render(outcome, renderLineNumbers));
                        if (writeSidecarMetadata) {
                            SidecarMetadataSupport.writeFile(javaFile, DecompilationJson.toJson(outcome));
                        }
                        JsonObject savedEntry = new JsonObject();
                        savedEntry.addProperty("className", location.displayName());
                        savedEntry.addProperty("savedTo", javaFile.toString());
                        if (writeSidecarMetadata) {
                            savedEntry.addProperty("savedMetadataTo", SidecarMetadataSupport.sidecarPath(javaFile).toString());
                        }
                        DecompilationJson.addOutcomeSummary(savedEntry, outcome);
                        saved.add(savedEntry);
                    } catch (Exception e) {
                        failures.add(failure(location, e));
                    }
                    reporter.report(++idx, total);
                }
            }

            if (!"sources-jar".equals(format)) {
                resourcesCopied = new JsonArray();
                for (ResourceEntry resource : container.listResources()) {
                    try {
                        byte[] bytes = container.loadResourceBytes(resource.entryName());
                        if (bytes == null) {
                            continue;
                        }
                        Path resourceFile = output.resolve(resource.entryName());
                        if (resourceFile.toString().endsWith(".java") && Files.exists(resourceFile)) {
                            continue;
                        }
                        Files.createDirectories(resourceFile.getParent());
                        Files.write(resourceFile, bytes);
                        JsonObject resourceEntry = new JsonObject();
                        resourceEntry.addProperty("resourceName", resource.entryName());
                        resourceEntry.addProperty("savedTo", resourceFile.toString());
                        resourceEntry.addProperty("size", bytes.length);
                        resourcesCopied.add(resourceEntry);
                    } catch (Exception e) {
                        JsonObject failure = new JsonObject();
                        failure.addProperty("resourceName", resource.entryName());
                        failure.addProperty("error", e.getMessage());
                        failures.add(failure);
                    }
                }
            }
            }
        }

        JsonObject structured = new JsonObject();
        structured.addProperty("output", output.toString());
        structured.addProperty("format", format);
        structured.addProperty("savedCount", saved.size());
        structured.addProperty("failureCount", failures.size());
        structured.addProperty("resourceCount", resourcesCopied.size());
        structured.add("saved", saved);
        structured.add("failures", failures);
        structured.add("resourcesCopied", resourcesCopied);
        String text = "Saved " + saved.size() + " decompiled sources";
        if (resourcesCopied.size() > 0) {
            text += " and " + resourcesCopied.size() + " resources";
        }
        text += " to " + output + aggregatePatchSummary(saved);
        if (!failures.isEmpty()) {
            text += "\nFailures: " + failures.size();
        }
        return failures.isEmpty()
                ? ToolResults.structured(text, structured)
                : ToolResult.error(text, structured);
    }

    private static JsonObject failure(ClassLocation location, Exception e) {
        JsonObject failure = new JsonObject();
        failure.addProperty("className", location.displayName());
        failure.addProperty("internalName", location.internalName());
        failure.addProperty("error", e.getMessage());
        return failure;
    }

    private static String aggregatePatchSummary(JsonArray saved) {
        int patched = 0;
        int metadataRebuilt = 0;
        int metadataLimited = 0;
        int warnings = 0;
        for (var element : saved) {
            JsonObject item = element.getAsJsonObject();
            if (item.has("patched") && item.get("patched").getAsBoolean()) {
                patched++;
            }
            if (item.has("metadataRebuilt") && item.get("metadataRebuilt").getAsBoolean()) {
                metadataRebuilt++;
            }
            if (item.has("metadataLimited") && item.get("metadataLimited").getAsBoolean()) {
                metadataLimited++;
            }
            if (item.has("warnings")) {
                warnings += item.getAsJsonArray("warnings").size();
            }
        }
        if (patched == 0 && warnings == 0) {
            return "";
        }
        return "\nPatched: " + patched
                + "\nMetadata rebuilt: " + metadataRebuilt
                + "\nMetadata limited: " + metadataLimited
                + "\nWarnings: " + warnings;
    }
}
