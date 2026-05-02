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
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public class ShowBytecodeTool implements MCPTool {
    @Override
    public String getDescription() {
        return "Show javap bytecode for a class from a .class file, directory, or supported archive.";
    }

    @Override
    public JsonObject getInputSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        addStringProperty(properties, "path", "Path to a .class file, directory, or supported archive");
        addStringProperty(properties, "className", "Class name when path points to a directory or archive");
        addIntegerProperty(properties, "releaseVersion", "Target multi-release class version; defaults to the current runtime", Runtime.version().feature());
        addBooleanProperty(properties, "verbose", "Use javap -v", true);
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

        String className = JsonUtils.getString(arguments, "className", null);
        Integer releaseVersion = arguments.has("releaseVersion") && !arguments.get("releaseVersion").isJsonNull()
                ? JsonUtils.getInt(arguments, "releaseVersion", Runtime.version().feature())
                : null;
        boolean verbose = JsonUtils.getBoolean(arguments, "verbose", true);

        Path classFile = null;
        try (InputContainer container = InputContainers.open(path, releaseVersion)) {
            if (Files.isRegularFile(path) && path.toString().endsWith(".class") && className == null) {
                classFile = path;
            } else {
                String requestedClass = className;
                if (requestedClass == null || requestedClass.isBlank()) {
                    var defaultClass = container.defaultClass();
                    if (defaultClass == null) {
                        throw new IllegalArgumentException("className is required when the input contains multiple classes");
                    }
                    requestedClass = defaultClass.internalName();
                }

                byte[] bytes = container.loadClassBytes(requestedClass);
                if (bytes == null) {
                    throw new IllegalArgumentException("Class not found: " + requestedClass);
                }
                classFile = Files.createTempFile("jd-mcp-bytecode-", ".class");
                Files.write(classFile, bytes);
            }

            String bytecode = runJavap(classFile, verbose);
            JsonObject structured = new JsonObject();
            structured.addProperty("path", path.toString());
            structured.addProperty("classFile", classFile.toString());
            structured.addProperty("verbose", verbose);
            structured.addProperty("bytecode", bytecode);
            return ToolResults.structured(bytecode, structured);
        } finally {
            if (classFile != null && !classFile.equals(path)) {
                Files.deleteIfExists(classFile);
            }
        }
    }

    private static String runJavap(Path classFile, boolean verbose) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("javap");
        if (verbose) {
            command.add("-v");
        }
        command.add("-c");
        command.add("-p");
        command.add(classFile.toString());

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("javap execution failed, exit code: " + exitCode);
        }
        return output;
    }

    private static void addStringProperty(JsonObject properties, String name, String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "string");
        property.addProperty("description", description);
        properties.add(name, property);
    }

    private static void addBooleanProperty(JsonObject properties, String name, String description, boolean defaultValue) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "boolean");
        property.addProperty("description", description);
        property.addProperty("default", defaultValue);
        properties.add(name, property);
    }

    private static void addIntegerProperty(JsonObject properties, String name, String description, int defaultValue) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "integer");
        property.addProperty("description", description);
        property.addProperty("default", defaultValue);
        properties.add(name, property);
    }
}
