package tools;

import model.ToolResult;
import com.google.gson.JsonObject;

public class AdvancedDecompileTool extends DecompileClassTool {
    @Override
    public String getDescription() {
        return "Advanced decompilation with auto engine selection and method-level JD-Core v1/v0 patching.";
    }

    @Override
    public ToolResult execute(JsonObject arguments) throws Exception {
        if (!arguments.has("engine")) {
            arguments.addProperty("engine", "auto");
        }
        return super.execute(arguments);
    }
}
