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

            if (limit > 0 && paths.size() > limit) {
                paths = paths.subList(0, limit);
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

            JsonObject structured = new JsonObject();
            structured.addProperty("path", directory.toString());
            structured.addProperty("archiveCount", archives.size());
            structured.addProperty("totalClasses", totalClasses);
            structured.addProperty("totalSize", totalSize);
            archives = JsonUtils.paginate(archives, offset, limit);
            structured.add("archives", archives);
            return ToolResults.structured(text.toString().trim(), structured);
        }
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
