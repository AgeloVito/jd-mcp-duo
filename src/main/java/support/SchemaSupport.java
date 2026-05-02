package support;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class SchemaSupport {
    private SchemaSupport() {
    }

    public static JsonObject objectSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        schema.add("required", new JsonArray());
        return schema;
    }

    public static JsonObject properties(JsonObject schema) {
        return schema.getAsJsonObject("properties");
    }

    public static void require(JsonObject schema, String name) {
        schema.getAsJsonArray("required").add(name);
    }

    public static void addString(JsonObject properties, String name, String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "string");
        property.addProperty("description", description);
        properties.add(name, property);
    }

    public static void addBoolean(JsonObject properties, String name, String description, boolean defaultValue) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "boolean");
        property.addProperty("description", description);
        property.addProperty("default", defaultValue);
        properties.add(name, property);
    }

    public static void addInteger(JsonObject properties, String name, String description, int defaultValue) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "integer");
        property.addProperty("description", description);
        property.addProperty("default", defaultValue);
        properties.add(name, property);
    }

    public static void addStringOrArray(JsonObject properties, String name, String description) {
        JsonObject property = new JsonObject();
        JsonArray types = new JsonArray();
        types.add("string");
        types.add("array");
        property.add("type", types);
        property.addProperty("description", description);
        properties.add(name, property);
    }
}
