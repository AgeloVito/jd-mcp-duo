package model;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;

public record ToolResult(String text, JsonElement structuredData, boolean isError) {

    public ToolResult {
        if (text == null) {
            text = "";
        }
        if (structuredData == null) {
            structuredData = JsonNull.INSTANCE;
        }
    }

    public static ToolResult text(String text) {
        return new ToolResult(text, JsonNull.INSTANCE, false);
    }

    public static ToolResult structured(String text, JsonElement structuredData) {
        return new ToolResult(text, structuredData, false);
    }

    public static ToolResult error(String text) {
        return new ToolResult(text, JsonNull.INSTANCE, true);
    }

    public static ToolResult error(String text, JsonElement structuredData) {
        return new ToolResult(text, structuredData, true);
    }

    public boolean hasStructuredData() {
        return structuredData != null && !structuredData.isJsonNull();
    }
}
