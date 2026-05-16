package tools;

import decompile.EngineCatalog;
import model.MCPTool;
import model.ToolResult;
import support.JsonUtils;
import support.SchemaSupport;
import support.ToolResults;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class DescribeEngineOptionsTool implements MCPTool {
    @Override
    public String getDescription() {
        return "Describe supported options for a decompiler engine.";
    }

    @Override
    public JsonObject getInputSchema() {
        JsonObject schema = SchemaSupport.objectSchema();
        SchemaSupport.addString(SchemaSupport.properties(schema), "engine", "Decompiler engine name");
        SchemaSupport.require(schema, "engine");
        return schema;
    }

    @Override
    public ToolResult execute(JsonObject arguments) {
        String engine = JsonUtils.getString(arguments, "engine", "");
        JsonObject structured = EngineCatalog.engineJson(engine);
        if (structured == null) {
            return ToolResults.error("Unknown engine: " + engine);
        }
        return ToolResults.structured(renderText(structured), structured);
    }

    private String renderText(JsonObject structured) {
        StringBuilder text = new StringBuilder();
        text.append("Options for ").append(structured.get("engine").getAsString()).append('\n');
        text.append(structured.get("description").getAsString()).append('\n');

        JsonArray profiles = structured.getAsJsonArray("profiles");
        if (profiles != null && !profiles.isEmpty()) {
            text.append("\nProfiles: ");
            for (int i = 0; i < profiles.size(); i++) {
                if (i > 0) {
                    text.append(", ");
                }
                text.append(profiles.get(i).getAsString());
            }
            text.append('\n');
        }

        if (structured.has("aliases")) {
            text.append("\nAliases: ");
            JsonArray aliases = structured.getAsJsonArray("aliases");
            for (int i = 0; i < aliases.size(); i++) {
                if (i > 0) {
                    text.append(", ");
                }
                text.append(aliases.get(i).getAsString());
            }
            text.append('\n');
        }

        text.append("\nPass raw engine options via --preferences='{\"key\":\"value\"}'.\n");
        text.append("Available options:\n");
        for (JsonElement element : structured.getAsJsonArray("options")) {
            JsonObject option = element.getAsJsonObject();
            text.append("- ").append(option.get("name").getAsString());
            if (option.has("type")) {
                text.append(" (").append(option.get("type").getAsString()).append(')');
            }
            if (option.has("defaultValue") && !option.get("defaultValue").isJsonNull()) {
                text.append(", default=").append(option.get("defaultValue").getAsString());
            }
            if (option.has("description")) {
                text.append(": ").append(option.get("description").getAsString());
            }
            text.append('\n');
        }

        return text.toString().trim();
    }
}
