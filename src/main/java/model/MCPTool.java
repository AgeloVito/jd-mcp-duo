package model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import support.ProgressReporter;

/**
 * MCP Tool Interface
 */
public interface MCPTool {
    /**
     * Get tool description
     */
    String getDescription();

    /**
     * Get input parameter schema
     */
    JsonObject getInputSchema();

    /**
     * Get output schema
     */
    default JsonObject getOutputSchema() {
        return null;
    }

    /**
     * Execute tool
     *
     * @param arguments Input parameters
     * @return Execution result
     * @throws Exception Execution exception
     */
    ToolResult execute(JsonObject arguments) throws Exception;

    /**
     * Execute tool with progress reporting.
     * Default implementation delegates to execute(arguments) for backward compatibility.
     */
    default ToolResult execute(JsonObject arguments, ProgressReporter reporter) throws Exception {
        return execute(arguments);
    }

    /**
     * Build a CLI usage example string from the tool's input schema.
     */
    static String buildUsageExample(String toolName, JsonObject schema) {
        JsonObject properties = schema.has("properties") ? schema.getAsJsonObject("properties") : new JsonObject();
        JsonArray required = schema.has("required") ? schema.getAsJsonArray("required") : new JsonArray();
        StringBuilder sb = new StringBuilder(toolName);
        for (String key : properties.keySet()) {
            sb.append(" --").append(key).append("=");
            boolean isRequired = false;
            for (int i = 0; i < required.size(); i++) {
                if (key.equals(required.get(i).getAsString())) {
                    isRequired = true;
                    break;
                }
            }
            sb.append(isRequired ? "<" + key + ">" : "[" + key + "]");
        }
        return sb.toString();
    }
}
