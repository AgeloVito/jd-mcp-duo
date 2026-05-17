package tools;

import model.ToolResult;
import support.JsonUtils;
import support.SchemaSupport;
import com.google.gson.JsonObject;

public class CompareJdCoreTool extends CompareClassTool {
    @Override
    public String getDescription() {
        return "Compare JD-Core v0 and JD-Core v1 decompiled output for the same class.";
    }

    @Override
    public JsonObject getInputSchema() {
        JsonObject schema = SchemaSupport.objectSchema();
        JsonObject properties = SchemaSupport.properties(schema);
        SchemaSupport.addString(properties, "path", "Class/archive path to compare with JD-Core v0 and v1");
        SchemaSupport.addString(properties, "className", "Class name when needed");
        SchemaSupport.addString(properties, "rightPath", "Optional second class/archive path; defaults to path");
        SchemaSupport.addString(properties, "rightClassName", "Optional second class name; defaults to className");
        SchemaSupport.addInteger(properties, "releaseVersion", "Target multi-release class version; defaults to the current runtime", Runtime.version().feature());
        SchemaSupport.addInteger(properties, "attemptTimeoutMillis", "Per-engine attempt timeout in milliseconds; 0 disables timeout", (int) decompile.DecompilerOptions.DEFAULT_ATTEMPT_TIMEOUT_MILLIS);
        SchemaSupport.addBoolean(properties, "lineNumbers", "Include line-number metadata", false);
        SchemaSupport.addBoolean(properties, "advancedLookup", "Search sibling archives for dependency resolution; JDK modules are included by default", false);
        SchemaSupport.addStringOrArray(properties, "classpath", "Additional classpath entries");
        SchemaSupport.addString(properties, "leftPath", "Left class/archive path; defaults to path");
        SchemaSupport.addString(properties, "leftClassName", "Left class name; defaults to className");
        JsonObject prefs = new JsonObject();
        prefs.addProperty("type", "object");
        prefs.addProperty("description", "Per-engine raw preferences passed to transformer-api");
        properties.add("preferences", prefs);
        SchemaSupport.require(schema, "path");
        return schema;
    }

    @Override
    public ToolResult execute(JsonObject arguments) throws Exception {
        JsonObject normalized = arguments.deepCopy();
        String path = JsonUtils.getString(normalized, "path", null);
        if (!normalized.has("leftPath")) {
            normalized.addProperty("leftPath", path);
        }
        if (!normalized.has("rightPath")) {
            normalized.addProperty("rightPath", JsonUtils.getString(normalized, "leftPath", path));
        }
        String className = JsonUtils.getString(normalized, "className", null);
        if (className != null && !className.isBlank() && !normalized.has("leftClassName")) {
            normalized.addProperty("leftClassName", className);
        }
        if (!normalized.has("rightClassName")) {
            normalized.addProperty("rightClassName", JsonUtils.getString(normalized, "leftClassName", className));
        }
        normalized.addProperty("leftEngine", "jd-core-v0");
        normalized.addProperty("rightEngine", "jd-core-v1");
        return super.execute(normalized);
    }
}
