package tools;

import model.MCPTool;
import model.ToolResult;
import support.JsonUtils;
import support.SchemaSupport;
import support.ToolResults;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarFile;

public class ListDependenciesTool implements MCPTool {
    @Override
    public String getDescription() {
        return "List all embedded Maven dependencies found in an archive by scanning META-INF/maven/**/pom.properties.";
    }

    @Override
    public JsonObject getInputSchema() {
        JsonObject schema = SchemaSupport.objectSchema();
        JsonObject properties = SchemaSupport.properties(schema);
        SchemaSupport.addString(properties, "path", "Path to a supported archive");
        SchemaSupport.addString(properties, "format", "Output format: json (default) or text (GAV per line)");
        SchemaSupport.addString(properties, "output", "Optional file path to write the dependency list");
        SchemaSupport.addInteger(properties, "limit", "Maximum dependencies to return", 500);
        SchemaSupport.addInteger(properties, "offset", "Number of results to skip for pagination", 0);
        SchemaSupport.require(schema, "path");
        return schema;
    }

    @Override
    public ToolResult execute(JsonObject arguments) throws Exception {
        Path path = JsonUtils.getRequiredPath(arguments, "path");
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("File not found: " + path);
        }
        String format = JsonUtils.getString(arguments, "format", "json");
        Path outputFile = JsonUtils.getPath(arguments, "output");
        int limit = JsonUtils.getInt(arguments, "limit", 500);
        int offset = JsonUtils.getInt(arguments, "offset", 0);

        List<Dep> deps = new ArrayList<>();
        try (JarFile jar = new JarFile(path.toFile())) {
            jar.stream()
                    .filter(entry -> !entry.isDirectory())
                    .filter(entry -> {
                        String name = entry.getName();
                        return name.startsWith("META-INF/maven/") && name.endsWith("/pom.properties");
                    })
                    .sorted((a, b) -> a.getName().compareTo(b.getName()))
                    .forEach(entry -> {
                        try {
                            Properties props = new Properties();
                            props.load(jar.getInputStream(entry));
                            String g = props.getProperty("groupId");
                            String a = props.getProperty("artifactId");
                            String v = props.getProperty("version");
                            if (g != null && a != null && v != null) {
                                deps.add(new Dep(g, a, v));
                            }
                        } catch (Exception ignored) {
                        }
                    });
        }

        int totalDeps = deps.size();
        List<Dep> shown = deps;
        if (limit > 0 && shown.size() > limit) {
            shown = shown.subList(0, limit);
        }

        if (outputFile != null) {
            if (outputFile.getParent() != null) {
                Files.createDirectories(outputFile.getParent());
            }
            StringBuilder sb = new StringBuilder();
            for (Dep d : shown) {
                sb.append(d.gav()).append('\n');
            }
            Files.writeString(outputFile, sb.toString());
        }

        if ("text".equals(format)) {
            StringBuilder sb = new StringBuilder();
            for (Dep d : shown) {
                sb.append(d.gav()).append('\n');
            }
            return ToolResult.text(sb.toString().trim());
        }

        JsonArray jsonDeps = new JsonArray();
        for (Dep d : shown) {
            JsonObject o = new JsonObject();
            o.addProperty("groupId", d.groupId);
            o.addProperty("artifactId", d.artifactId);
            o.addProperty("version", d.version);
            o.addProperty("gav", d.gav());
            jsonDeps.add(o);
        }

        JsonObject structured = new JsonObject();
        structured.addProperty("path", path.toString());
        structured.addProperty("totalCount", totalDeps);
        structured.addProperty("showing", shown.size());
        int _total = totalDeps;
            jsonDeps = JsonUtils.paginate(jsonDeps, offset, limit);
        structured.addProperty("totalResults", _total);
        structured.addProperty("offset", offset);
        structured.add("dependencies", jsonDeps);

        String text = shown.size() + " embedded dependencies found in " + path;
        if (outputFile != null) {
            text += ", saved to " + outputFile;
        }
        return ToolResults.structured(text, structured);
    }

    private record Dep(String groupId, String artifactId, String version) {
        String gav() {
            return groupId + ":" + artifactId + ":" + version;
        }
    }
}
