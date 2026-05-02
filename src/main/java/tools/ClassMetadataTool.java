package tools;

import archive.InputContainer;
import archive.InputContainers;
import model.MCPTool;
import model.ToolResult;
import support.AsmSupport;
import support.JsonUtils;
import support.SchemaSupport;
import support.ToolResults;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;

public class ClassMetadataTool implements MCPTool {
    @Override
    public String getDescription() {
        return "Inspect class-level metadata, methods, fields, annotations, and bytecode version.";
    }

    @Override
    public JsonObject getInputSchema() {
        JsonObject schema = SchemaSupport.objectSchema();
        JsonObject properties = SchemaSupport.properties(schema);
        SchemaSupport.addString(properties, "path", "Path to a class file, archive, or directory");
        SchemaSupport.addString(properties, "className", "Class name when the input contains multiple classes");
        SchemaSupport.addInteger(properties, "releaseVersion", "Target multi-release class version; defaults to the current runtime", Runtime.version().feature());
        SchemaSupport.require(schema, "path");
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
        try (InputContainer container = InputContainers.open(path, releaseVersion)) {
            if (className == null || className.isBlank()) {
                var defaultClass = container.defaultClass();
                if (defaultClass == null) {
                    throw new IllegalArgumentException("className is required when the input contains multiple classes");
                }
                className = defaultClass.internalName();
            }
            JsonObject structured = AsmSupport.classMetadataJson(AsmSupport.readClassNode(container, className));
            return ToolResults.structured("Metadata for " + structured.get("displayName").getAsString(), structured);
        }
    }
}
