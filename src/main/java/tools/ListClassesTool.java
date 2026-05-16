package tools;

import archive.ClassLocation;
import archive.InputContainer;
import archive.InputContainers;
import model.MCPTool;
import model.ToolResult;
import support.JsonUtils;
import support.SchemaSupport;
import support.ToolResults;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class ListClassesTool implements MCPTool {
    @Override
    public String getDescription() {
        return "List classes from a supported archive or directory with normalized class names and package statistics.";
    }

    @Override
    public JsonObject getInputSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        SchemaSupport.addString(properties, "path", "Path to an archive or directory");
        SchemaSupport.addString(properties, "package", "Optional package prefix filter");
        SchemaSupport.addInteger(properties, "releaseVersion", "Target multi-release class version; defaults to the current runtime", Runtime.version().feature());
        SchemaSupport.addBoolean(properties, "includeInner", "Include inner classes", false);
        SchemaSupport.addBoolean(properties, "detailed", "Include package statistics", false);
        SchemaSupport.addInteger(properties, "limit", "Maximum classes to return", 200);
        SchemaSupport.addInteger(properties, "offset", "Number of results to skip for pagination", 0);
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

        String packageFilter = JsonUtils.getString(arguments, "package", null);
        Integer releaseVersion = arguments.has("releaseVersion") && !arguments.get("releaseVersion").isJsonNull()
                ? JsonUtils.getInt(arguments, "releaseVersion", Runtime.version().feature())
                : null;
        boolean includeInner = JsonUtils.getBoolean(arguments, "includeInner", false);
        boolean detailed = JsonUtils.getBoolean(arguments, "detailed", false);
        int limit = JsonUtils.getInt(arguments, "limit", 200);
        int offset = JsonUtils.getInt(arguments, "offset", 0);

        try (InputContainer container = InputContainers.open(path, releaseVersion)) {
            List<ClassLocation> classes = container.listClasses(true).stream()
                    .filter(location -> includeInner || !location.internalName().contains("$"))
                    .filter(location -> packageFilter == null || location.displayName().startsWith(packageFilter))
                    .toList();

            int totalCount = classes.size();
            if (limit > 0 && classes.size() > limit) {
                classes = classes.subList(0, limit);
            }

            Map<String, Integer> packageCounts = new TreeMap<>();
            JsonArray classArray = new JsonArray();
            StringBuilder text = new StringBuilder();
            text.append("Classes in ").append(path).append('\n');
            text.append("Kind: ").append(container.kind()).append('\n');
            text.append("Total: ").append(totalCount).append("\n\n");

            for (ClassLocation location : classes) {
                String packageName = location.displayName().contains(".")
                        ? location.displayName().substring(0, location.displayName().lastIndexOf('.'))
                        : "(default)";
                packageCounts.merge(packageName, 1, Integer::sum);

                JsonObject classJson = new JsonObject();
                classJson.addProperty("internalName", location.internalName());
                classJson.addProperty("displayName", location.displayName());
                classJson.addProperty("entryName", location.entryName());
                if (location.multiReleaseVersion() != null) {
                    classJson.addProperty("multiReleaseVersion", location.multiReleaseVersion());
                }
                classArray.add(classJson);

                text.append("- ").append(location.displayName());
                if (location.multiReleaseVersion() != null) {
                    text.append(" [MR-").append(location.multiReleaseVersion()).append(']');
                }
                text.append('\n');
            }

            JsonObject structured = new JsonObject();
            structured.addProperty("path", path.toString());
            structured.addProperty("kind", container.kind());
            structured.addProperty("totalClasses", totalCount);
            structured.addProperty("showing", classes.size());
            if (totalCount > classes.size()) {
                text.append("... ").append(totalCount - classes.size()).append(" more classes not shown\n");
            }
            int _total = classArray.size();
            classArray = JsonUtils.paginate(classArray, offset, limit);
            structured.addProperty("totalResults", _total);
            structured.addProperty("offset", offset);
            structured.add("classes", classArray);
            if (detailed) {
                JsonObject packages = new JsonObject();
                packageCounts.forEach(packages::addProperty);
                structured.add("packages", packages);
            }
            return ToolResults.structured(text.toString().trim(), structured);
        }
    }
}
