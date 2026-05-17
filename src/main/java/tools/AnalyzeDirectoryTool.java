package tools;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;

import archive.InputContainer;
import archive.InputContainers;
import model.MCPTool;
import model.ToolResult;
import support.JsonUtils;
import support.SchemaSupport;
import support.ToolResults;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.stream.Stream;

public class AnalyzeDirectoryTool implements MCPTool {
    @Override
    public String getDescription() {
        return "Analyze supported archives under a directory and summarize class counts and sizes.";
    }

    @Override
    public JsonObject getInputSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        SchemaSupport.addString(properties, "path", "Directory path");
        SchemaSupport.addBoolean(properties, "recursive", "Recursively scan subdirectories", false);
        SchemaSupport.addString(properties, "pattern", "Optional glob pattern");
        SchemaSupport.addInteger(properties, "limit", "Maximum archives to return", 200);
        SchemaSupport.addInteger(properties, "offset", "Number of results to skip for pagination", 0);
        SchemaSupport.addString(properties, "output", "Optional output file path to write full results");
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("path");
        schema.add("required", required);
        return schema;
    }

    @Override
    public ToolResult execute(JsonObject arguments) throws Exception {
        Path directory = JsonUtils.getRequiredPath(arguments, "path");
        boolean recursive = JsonUtils.getBoolean(arguments, "recursive", false);
        String pattern = JsonUtils.getString(arguments, "pattern", "*");
        int limit = JsonUtils.getInt(arguments, "limit", 200);
        int offset = JsonUtils.getInt(arguments, "offset", 0);
        Path outputPath = extractOutputPath(arguments);
        int effectiveOffset = outputPath != null ? 0 : offset;
        int effectiveLimit = outputPath != null ? 0 : limit;
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Path must be a directory: " + directory);
        }

        JsonArray archives = new JsonArray();
        StringBuilder text = new StringBuilder();
        text.append("Archive analysis for ").append(directory).append("\n\n");

        try (Stream<Path> stream = recursive ? Files.walk(directory) : Files.list(directory)) {
            List<Path> paths = stream.filter(Files::isRegularFile)
                    .filter(path -> matchesPattern(path, pattern))
                    .filter(InputContainers::isArchivePath)
                    .sorted()
                    .toList();

            int totalArchives = paths.size();
            if (outputPath == null) {
                int start = Math.min(effectiveOffset, totalArchives);
                int end = effectiveLimit > 0 ? Math.min(effectiveOffset + effectiveLimit, totalArchives) : totalArchives;
                paths = paths.subList(start, end);
            }

            long totalClasses = 0;
            long totalSize = 0;
            for (Path path : paths) {
                try (InputContainer container = InputContainers.open(path)) {
                    int classCount = container.listClasses(true).size();
                    long size = Files.size(path);
                    totalClasses += classCount;
                    totalSize += size;

                    JsonObject archive = new JsonObject();
                    archive.addProperty("path", directory.relativize(path).toString());
                    archive.addProperty("kind", container.kind());
                    archive.addProperty("classCount", classCount);
                    archive.addProperty("size", size);
                    archives.add(archive);

                    text.append("- ").append(directory.relativize(path)).append(": ")
                            .append(classCount).append(" classes, ")
                            .append(size).append(" bytes\n");
                }
            }

            if (outputPath != null) {
                Files.writeString(outputPath, text.toString());
                truncateToPreview(text, archives, directory.toString(), totalArchives);
            }

            JsonObject structured = new JsonObject();
            structured.addProperty("path", directory.toString());
            structured.addProperty("archiveCount", outputPath != null ? totalArchives : archives.size());
            structured.addProperty("totalClasses", totalClasses);
            structured.addProperty("totalSize", totalSize);
            if (outputPath != null) {
                structured.addProperty("outputFile", outputPath.toString());
            }
            int _total = totalArchives;
            structured.addProperty("totalResults", _total);
            structured.addProperty("offset", offset);
            structured.add("archives", archives);
            return ToolResults.structured(text.toString().trim(), structured);
        }
    }

    private static Path extractOutputPath(JsonObject arguments) {
        String outputStr = JsonUtils.getString(arguments, "output", "");
        return outputStr.isBlank() ? null : JsonUtils.getPath(arguments, "output");
    }

    private static void truncateToPreview(StringBuilder text, JsonArray results,
                                           String directory, int totalCount) {
        int previewCount = Math.min(results.size(), 20);
        JsonArray preview = new JsonArray();
        for (int i = 0; i < previewCount; i++) {
            preview.add(results.get(i));
        }
        while (results.size() > 0) {
            results.remove(results.size() - 1);
        }
        for (int i = 0; i < preview.size(); i++) {
            results.add(preview.get(i));
        }
        StringBuilder previewText = new StringBuilder();
        previewText.append("Archive analysis for ").append(directory).append('\n');
        previewText.append("Full results (").append(totalCount).append(" archives) written to file.\n\n");
        String[] allLines = text.toString().split("\\R");
        int shown = 0;
        for (int i = 2; i < allLines.length && shown < previewCount; i++) {
            previewText.append(allLines[i]).append('\n');
            shown++;
        }
        text.setLength(0);
        text.append(previewText);
    }

    private static boolean matchesPattern(Path path, String pattern) {
        if ("*".equals(pattern)) {
            return true;
        }
        try {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            return matcher.matches(path.getFileName());
        } catch (java.util.regex.PatternSyntaxException e) {
            return path.getFileName().toString().matches(
                    java.util.regex.Pattern.quote(pattern).replace("\\*", "\\E.*\\Q").replace("\\?", "\\E.\\Q"));
        }
    }
}
