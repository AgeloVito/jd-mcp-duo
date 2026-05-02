package tools;

import archive.ClassLocation;
import archive.InputContainer;
import archive.InputContainers;
import archive.ResourceEntry;
import decompile.DecompilationJson;
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
import java.util.List;
import java.util.stream.Stream;

import static decompile.DecompilerEngines.AUTO;

public class DecompileDirectoryTool implements MCPTool {
    @Override
    public String getDescription() {
        return "Recursively decompile .class files and supported archives from a directory into a target directory while preserving relative structure.";
    }

    @Override
    public JsonObject getInputSchema() {
        JsonObject schema = SchemaSupport.objectSchema();
        JsonObject properties = SchemaSupport.properties(schema);
        SchemaSupport.addString(properties, "path", "Input directory containing class files and/or supported archives");
        SchemaSupport.addString(properties, "outputDir", "Output directory");
        SchemaSupport.addBoolean(properties, "recursive", "Recursively scan subdirectories", true);
        SchemaSupport.addString(properties, "engine", "Decompiler engine");
        SchemaSupport.addString(properties, "profile", "fast, accurate, or debuggable");
        SchemaSupport.addInteger(properties, "releaseVersion", "Target multi-release class version; defaults to the current runtime", Runtime.version().feature());
        SchemaSupport.addInteger(properties, "attemptTimeoutMillis", "Per-engine attempt timeout in milliseconds; 0 disables timeout", (int) decompile.DecompilerOptions.DEFAULT_ATTEMPT_TIMEOUT_MILLIS);
        SchemaSupport.addBoolean(properties, "lineNumbers", "Include line-number metadata", false);
        SchemaSupport.addString(properties, "renderLineNumbers", "Render visible line numbers in exported files: decompiled, source, both, or none");
        SchemaSupport.addBoolean(properties, "writeSidecarMetadata", "Write .meta.json sidecars next to exported sources", false);
        SchemaSupport.addBoolean(properties, "advancedLookup", "Search sibling archives for dependency resolution; JDK modules are included by default", false);
        SchemaSupport.addStringOrArray(properties, "classpath", "Additional classpath entries");
        SchemaSupport.addBoolean(properties, "summaryOnly", "Only return summary text without listing every written file; files are still written", false);
        SchemaSupport.addInteger(properties, "fileLimit", "Maximum input files processed, 0 for unlimited", 0);
        SchemaSupport.require(schema, "path");
        SchemaSupport.require(schema, "outputDir");
        return schema;
    }

    @Override
    public ToolResult execute(JsonObject arguments) throws Exception {
        Path root = JsonUtils.getRequiredPath(arguments, "path");
        Path outputDir = JsonUtils.getRequiredPath(arguments, "outputDir");
        boolean recursive = JsonUtils.getBoolean(arguments, "recursive", true);
        boolean summaryOnly = JsonUtils.getBoolean(arguments, "summaryOnly", false);
        int fileLimit = JsonUtils.getInt(arguments, "fileLimit", 0);
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Path must be a directory: " + root);
        }
        Files.createDirectories(outputDir);

        DecompilerOptions options = DecompilerOptions.fromArguments(arguments, AUTO);
        String renderLineNumbers = LineNumberRenderer.normalize(JsonUtils.getString(arguments, "renderLineNumbers", null));
        boolean writeSidecarMetadata = JsonUtils.getBoolean(arguments, "writeSidecarMetadata", false);
        JsonArray processed = new JsonArray();
        JsonArray failures = new JsonArray();
        int classFileCount = 0;
        int archiveCount = 0;
        int sourcesWritten = 0;
        int sourceFailures = 0;
        int patchedSources = 0;
        int metadataRebuiltSources = 0;
        int metadataLimitedSources = 0;
        int warningCount = 0;
        int resourceCount = 0;
        int resourceFailures = 0;

        try (Stream<Path> stream = recursive ? Files.walk(root) : Files.list(root)) {
            List<Path> inputs = stream.filter(Files::isRegularFile)
                    .sorted()
                    .toList();
            if (fileLimit > 0 && inputs.size() > fileLimit) {
                inputs = inputs.subList(0, fileLimit);
            }

            for (Path input : inputs) {
                Path relativeInput = root.relativize(input);
                JsonObject item = new JsonObject();
                item.addProperty("input", relativeInput.toString());
                try {
                    if (input.toString().endsWith(".class")) {
                        classFileCount++;
                        try (InputContainer container = InputContainers.open(input, options.releaseVersion());
                             decompile.DecompilerSession session = decompile.DecompilerSession.open(container, options)) {
                            var defaultClass = container.defaultClass();
                            if (defaultClass == null) {
                                throw new IllegalStateException("Failed to resolve class from " + input);
                            }
                            var outcome = session.decompile(defaultClass.internalName());
                            Path outputFile = outputDir.resolve(relativeInput.toString().replaceFirst("\\.class$", ".java"));
                            Files.createDirectories(outputFile.getParent());
                            Files.writeString(outputFile, LineNumberRenderer.render(outcome, renderLineNumbers));
                            if (writeSidecarMetadata) {
                                SidecarMetadataSupport.writeFile(outputFile, DecompilationJson.toJson(outcome));
                                item.addProperty("savedMetadataTo", SidecarMetadataSupport.sidecarPath(outputFile).toString());
                            }
                            item.addProperty("kind", "class");
                            item.addProperty("className", defaultClass.displayName());
                            item.addProperty("savedTo", outputFile.toString());
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
                            item.addProperty("successCount", 1);
                            item.addProperty("failureCount", 0);
                            if (outcome.patched()) {
                                patchedSources++;
                            }
                            if (outcome.metadataRebuilt()) {
                                metadataRebuiltSources++;
                            }
                            if (outcome.metadataLimited()) {
                                metadataLimitedSources++;
                            }
                            warningCount += outcome.warnings().size();
                            sourcesWritten++;
                        }
                    } else if (InputContainers.isArchivePath(input)) {
                        archiveCount++;
                        try (InputContainer container = InputContainers.open(input, options.releaseVersion());
                             decompile.DecompilerSession session = decompile.DecompilerSession.open(container, options)) {
                            JsonArray classResults = new JsonArray();
                            int successCount = 0;
                            int failureCount = 0;
                            Path archiveOutputRoot = outputDir.resolve(relativeInput.toString());
                            for (ClassLocation location : container.listClasses(false)) {
                                JsonObject classResult = new JsonObject();
                                classResult.addProperty("className", location.displayName());
                                classResult.addProperty("internalName", location.internalName());
                                try {
                                    var outcome = session.decompile(location.internalName());
                                    Path outputFile = archiveOutputRoot.resolve(location.internalName() + ".java");
                                    Files.createDirectories(outputFile.getParent());
                                    Files.writeString(outputFile, LineNumberRenderer.render(outcome, renderLineNumbers));
                                    if (writeSidecarMetadata) {
                                        SidecarMetadataSupport.writeFile(outputFile, DecompilationJson.toJson(outcome));
                                        classResult.addProperty("savedMetadataTo", SidecarMetadataSupport.sidecarPath(outputFile).toString());
                                    }
                                    classResult.addProperty("success", true);
                                    classResult.addProperty("savedTo", outputFile.toString());
                                    classResult.addProperty("engineUsed", outcome.engineUsed());
                                    classResult.addProperty("patched", outcome.patched());
                                    classResult.addProperty("fallbackUsed", outcome.fallbackUsed());
                                    classResult.addProperty("metadataLimited", outcome.metadataLimited());
                                    classResult.addProperty("metadataRebuilt", outcome.metadataRebuilt());
                                    classResult.addProperty("methodPatchCount", outcome.methodPatches().size());
                                    classResult.add("methodPatches", DecompilationJson.methodPatchesJson(outcome));
                                    classResult.add("warnings", DecompilationJson.warningsJson(outcome));
                                    JsonArray attempted = new JsonArray();
                                    outcome.attemptedEngines().forEach(attempted::add);
                                    classResult.add("attemptedEngines", attempted);
                                    JsonObject engineFailures = new JsonObject();
                                    outcome.engineFailures().forEach(engineFailures::addProperty);
                                    classResult.add("engineFailures", engineFailures);
                                    if (outcome.patched()) {
                                        patchedSources++;
                                    }
                                    if (outcome.metadataRebuilt()) {
                                        metadataRebuiltSources++;
                                    }
                                    if (outcome.metadataLimited()) {
                                        metadataLimitedSources++;
                                    }
                                    warningCount += outcome.warnings().size();
                                    successCount++;
                                    sourcesWritten++;
                                } catch (Exception e) {
                                    classResult.addProperty("success", false);
                                    classResult.addProperty("error", e.getMessage());
                                    failureCount++;
                                    sourceFailures++;

                                    JsonObject failure = new JsonObject();
                                    failure.addProperty("input", relativeInput.toString());
                                    failure.addProperty("kind", "archive-class");
                                    failure.addProperty("className", location.displayName());
                                    failure.addProperty("error", e.getMessage());
                                    failures.add(failure);
                                }
                                classResults.add(classResult);
                            }

                            int archiveResourceCount = 0;
                            for (ResourceEntry resource : container.listResources()) {
                                try {
                                    byte[] bytes = container.loadResourceBytes(resource.entryName());
                                    if (bytes == null) {
                                        continue;
                                    }
                                    Path outputFile = archiveOutputRoot.resolve(resource.entryName());
                                    if (outputFile.toString().endsWith(".java") && Files.exists(outputFile)) {
                                        continue;
                                    }
                                    Files.createDirectories(outputFile.getParent());
                                    Files.write(outputFile, bytes);
                                    archiveResourceCount++;
                                    resourceCount++;
                                } catch (Exception e) {
                                    resourceFailures++;
                                }
                            }

                            item.addProperty("kind", "archive");
                            item.addProperty("archivePath", relativeInput.toString());
                            item.addProperty("classCount", container.listClasses(false).size());
                            item.addProperty("successCount", successCount);
                            item.addProperty("failureCount", failureCount);
                            item.addProperty("resourceCount", archiveResourceCount);
                            item.addProperty("outputRoot", archiveOutputRoot.toString());
                            item.add("classResults", classResults);
                        }
                    } else {
                        resourceCount++;
                        try {
                            Path outputFile = outputDir.resolve(relativeInput);
                            Files.createDirectories(outputFile.getParent());
                            Files.copy(input, outputFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            item.addProperty("kind", "resource");
                            item.addProperty("savedTo", outputFile.toString());
                            item.addProperty("size", Files.size(input));
                            item.addProperty("successCount", 1);
                            item.addProperty("failureCount", 0);
                        } catch (Exception e) {
                            resourceFailures++;
                            item.addProperty("kind", "resource");
                            item.addProperty("error", e.getMessage());
                            item.addProperty("successCount", 0);
                            item.addProperty("failureCount", 1);
                            failures.add(item);
                        }
                    }
                    processed.add(item);
                } catch (Exception e) {
                    sourceFailures++;
                    item.addProperty("error", e.getMessage());
                    item.addProperty("successCount", 0);
                    failures.add(item);
                }
            }
        }

        JsonObject structured = new JsonObject();
        structured.addProperty("path", root.toString());
        structured.addProperty("outputDir", outputDir.toString());
        structured.addProperty("classFileCount", classFileCount);
        structured.addProperty("archiveCount", archiveCount);
        structured.addProperty("sourcesWritten", sourcesWritten);
        structured.addProperty("sourceFailures", sourceFailures);
        structured.addProperty("patchedSources", patchedSources);
        structured.addProperty("metadataRebuiltSources", metadataRebuiltSources);
        structured.addProperty("metadataLimitedSources", metadataLimitedSources);
        structured.addProperty("warningCount", warningCount);
        structured.addProperty("resourceCount", resourceCount);
        structured.addProperty("resourceFailures", resourceFailures);
        structured.addProperty("summaryOnly", summaryOnly);
        structured.add("processed", processed);
        structured.add("failures", failures);

        StringBuilder text = new StringBuilder();
        text.append("Decompile directory complete\n");
        text.append("Input: ").append(root).append('\n');
        text.append("Output: ").append(outputDir).append('\n');
        text.append("Class files: ").append(classFileCount).append('\n');
        text.append("Archives: ").append(archiveCount).append('\n');
        text.append("Sources written: ").append(sourcesWritten).append('\n');
        if (patchedSources > 0 || warningCount > 0) {
            text.append("Patched: ").append(patchedSources).append('\n');
            text.append("Metadata rebuilt: ").append(metadataRebuiltSources).append('\n');
            text.append("Metadata limited: ").append(metadataLimitedSources).append('\n');
            text.append("Warnings: ").append(warningCount).append('\n');
        }
        text.append("Resources copied: ").append(resourceCount).append('\n');
        if (sourceFailures > 0) {
            text.append("Failures: ").append(sourceFailures).append('\n');
        }
        if (resourceFailures > 0) {
            text.append("Resource failures: ").append(resourceFailures).append('\n');
        }
        if (!summaryOnly) {
            processed.forEach(entry -> {
                JsonObject item = entry.getAsJsonObject();
                if (item.has("savedTo")) {
                    text.append("- ").append(item.get("savedTo").getAsString()).append('\n');
                } else if (item.has("outputRoot")) {
                    text.append("- ").append(item.get("archivePath").getAsString())
                            .append(" -> ").append(item.get("outputRoot").getAsString())
                            .append(" (ok=").append(item.get("successCount").getAsInt())
                            .append(", fail=").append(item.get("failureCount").getAsInt())
                            .append(")\n");
                }
            });
        }

        return ToolResults.structured(text.toString().trim(), structured);
    }
}
