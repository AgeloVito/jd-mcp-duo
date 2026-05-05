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

        if (outputFile != null) {
            if (outputFile.getParent() != null) {
                Files.createDirectories(outputFile.getParent());
            }
            StringBuilder sb = new StringBuilder();
            for (Dep d : deps) {
                sb.append(d.gav()).append('\n');
            }
            Files.writeString(outputFile, sb.toString());
        }

        if ("text".equals(format)) {
            StringBuilder sb = new StringBuilder();
            for (Dep d : deps) {
                sb.append(d.gav()).append('\n');
            }
            return ToolResult.text(sb.toString().trim());
        }

        JsonArray jsonDeps = new JsonArray();
        for (Dep d : deps) {
            JsonObject o = new JsonObject();
            o.addProperty("groupId", d.groupId);
            o.addProperty("artifactId", d.artifactId);
            o.addProperty("version", d.version);
            o.addProperty("gav", d.gav());
            jsonDeps.add(o);
        }

        JsonObject structured = new JsonObject();
        structured.addProperty("path", path.toString());
        structured.addProperty("count", deps.size());
        structured.add("dependencies", jsonDeps);

        String text = deps.size() + " embedded dependencies found in " + path;
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
