package support;

import model.ToolResult;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public final class ToolResults {
    private ToolResults() {
    }

    public static ToolResult text(String text) {
        return ToolResult.text(text);
    }

    public static ToolResult structured(String text, JsonObject structuredData) {
        return ToolResult.structured(text, structuredData);
    }

    public static ToolResult structured(String text, JsonElement structuredData) {
        return ToolResult.structured(text, structuredData);
    }

    public static ToolResult error(String text) {
        return ToolResult.error(text);
    }

    public static ToolResult error(String text, JsonObject structuredData) {
        return ToolResult.error(text, structuredData);
    }
}
