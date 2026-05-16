package support;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class JsonUtils {
    private JsonUtils() {
    }

    public static String getString(JsonObject object, String key, String defaultValue) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return defaultValue;
        }
        return object.get(key).getAsString();
    }

    public static boolean getBoolean(JsonObject object, String key, boolean defaultValue) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return defaultValue;
        }
        return object.get(key).getAsBoolean();
    }

    public static int getInt(JsonObject object, String key, int defaultValue) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return defaultValue;
        }
        return object.get(key).getAsInt();
    }

    public static JsonObject getObject(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonObject()) {
            return new JsonObject();
        }
        return object.getAsJsonObject(key);
    }

    public static List<String> getStringList(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return Collections.emptyList();
        }

        JsonElement element = object.get(key);
        if (element.isJsonArray()) {
            List<String> values = new ArrayList<>();
            for (JsonElement item : element.getAsJsonArray()) {
                values.add(item.getAsString());
            }
            return values;
        }

        if (element.isJsonPrimitive()) {
            String value = element.getAsString();
            if (value.isBlank()) {
                return Collections.emptyList();
            }
            String[] parts = value.split(",");
            List<String> values = new ArrayList<>(parts.length);
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    values.add(trimmed);
                }
            }
            return values;
        }

        return Collections.emptyList();
    }

    public static JsonElement parseCliValue(String rawValue) {
        if (rawValue == null) {
            return new JsonPrimitive("");
        }

        String trimmed = rawValue.trim();
        if (trimmed.isEmpty()) {
            return new JsonPrimitive("");
        }

        if ((trimmed.startsWith("{") && trimmed.endsWith("}"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            return JsonParser.parseString(trimmed);
        }
        if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
            return new JsonPrimitive(Boolean.parseBoolean(trimmed));
        }
        if (trimmed.matches("-?\\d+")) {
            return new JsonPrimitive(Long.parseLong(trimmed));
        }
        if (trimmed.matches("-?\\d+\\.\\d+")) {
            return new JsonPrimitive(Double.parseDouble(trimmed));
        }
        return new JsonPrimitive(rawValue);
    }

    public static JsonArray toJsonArray(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    /**
     * Slice a JsonArray for pagination (offset + limit).
     * Returns a new array containing items from offset to offset+limit.
     */
    public static JsonArray paginate(JsonArray array, int offset, int limit) {
        if (offset < 0) offset = 0;
        if (limit <= 0) return array;
        JsonArray result = new JsonArray();
        int end = Math.min(array.size(), offset + limit);
        for (int i = Math.min(offset, array.size()); i < end; i++) {
            result.add(array.get(i));
        }
        return result;
    }

    public static Path getRequiredPath(JsonObject object, String key) {
        String raw = getString(object, key, "");
        if (raw.isBlank()) {
            throw new IllegalArgumentException("Missing required parameter: " + key);
        }
        Path path = PathSupport.validatePath(raw);
        if (path == null) {
            throw new IllegalArgumentException("Invalid or unsafe path (" + key + "): " + raw);
        }
        return path;
    }

    public static Path getPath(JsonObject object, String key) {
        String raw = getString(object, key, "");
        if (raw.isBlank()) {
            return null;
        }
        return PathSupport.validatePath(raw);
    }
}
