package tools;

import archive.InputContainer;
import archive.InputContainers;
import model.MCPTool;
import model.ToolResult;
import support.JsonUtils;
import support.ToolResults;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
        addStringProperty(properties, "path", "Directory path");
        JsonObject recursive = new JsonObject();
        recursive.addProperty("type", "boolean");
        recursive.addProperty("default", false);
        properties.add("recursive", recursive);
        addStringProperty(properties, "pattern", "Optional glob pattern");
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
            structured.add("archives", archives);
            return ToolResults.structured(text.toString().trim(), structured);
        }
    }

    private static boolean matchesPattern(Path path, String pattern) {
        return "*".equals(pattern) || path.getFileName().toString().matches(globToRegex(pattern));
    }

    private static String globToRegex(String glob) {
        return glob.replace(".", "\\.").replace("*", ".*").replace("?", ".");
    }

    private static void addStringProperty(JsonObject properties, String name, String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "string");
        property.addProperty("description", description);
        properties.add(name, property);
    }
}
