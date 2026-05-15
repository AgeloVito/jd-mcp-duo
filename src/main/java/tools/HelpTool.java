package tools;

import com.google.gson.JsonObject;
import model.MCPTool;
import model.ToolResult;
import support.SchemaSupport;
import support.ToolResults;

import java.util.Map;

/**
 * Lightweight liveness check for MCP skills.
 * Confirms the server is alive and lists all available tool names.
 */
public class HelpTool implements MCPTool {
    private final Map<String, MCPTool> tools;

    public HelpTool(Map<String, MCPTool> tools) {
        this.tools = tools;
    }

    @Override
    public String getDescription() {
        return "Check that the MCP server is alive and list available tools.";
    }

    @Override
    public JsonObject getInputSchema() {
        JsonObject schema = SchemaSupport.objectSchema();
        schema.add("properties", new JsonObject());
        return schema;
    }

    @Override
    public ToolResult execute(JsonObject arguments) throws Exception {
        StringBuilder sb = new StringBuilder("jd-mcp-duo mcp server is alive. ");
        sb.append(tools.size()).append(" tools available: ");
        sb.append(String.join(", ", tools.keySet()));
        return ToolResults.text(sb.toString());
    }
}
