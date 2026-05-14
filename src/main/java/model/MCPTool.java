package model;

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
}
