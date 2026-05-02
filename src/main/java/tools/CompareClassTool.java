package tools;

import archive.InputContainer;
import archive.InputContainers;
import decompile.DecompilationOutcome;
import decompile.DecompilerOptions;
import model.MCPTool;
import model.ToolResult;
import support.JsonUtils;
import support.SchemaSupport;
import support.ToolResults;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;

import static decompile.DecompilerEngines.AUTO;
import static decompile.DecompilerSupport.decompile;

public class CompareClassTool implements MCPTool {
    @Override
    public String getDescription() {
        return "Compare decompiled source for two classes or for the same class under two engine configurations.";
    }

    @Override
    public JsonObject getInputSchema() {
        JsonObject schema = SchemaSupport.objectSchema();
        JsonObject properties = SchemaSupport.properties(schema);
        SchemaSupport.addString(properties, "leftPath", "Left class/archive path");
        SchemaSupport.addString(properties, "rightPath", "Right class/archive path; defaults to leftPath");
        SchemaSupport.addString(properties, "leftClassName", "Left class name when needed");
        SchemaSupport.addString(properties, "rightClassName", "Right class name when needed");
        SchemaSupport.addString(properties, "leftEngine", "Left decompiler engine");
        SchemaSupport.addString(properties, "rightEngine", "Right decompiler engine");
        SchemaSupport.addInteger(properties, "releaseVersion", "Target multi-release class version; defaults to the current runtime", Runtime.version().feature());
        SchemaSupport.addInteger(properties, "attemptTimeoutMillis", "Per-engine attempt timeout in milliseconds; 0 disables timeout", (int) decompile.DecompilerOptions.DEFAULT_ATTEMPT_TIMEOUT_MILLIS);
        SchemaSupport.addBoolean(properties, "lineNumbers", "Include line-number metadata", false);
        SchemaSupport.addBoolean(properties, "advancedLookup", "Search sibling archives for dependency resolution; JDK modules are included by default", false);
        SchemaSupport.addStringOrArray(properties, "classpath", "Additional classpath entries");
        SchemaSupport.require(schema, "leftPath");
        return schema;
    }

    @Override
    public ToolResult execute(JsonObject arguments) throws Exception {
        Path leftPath = JsonUtils.getRequiredPath(arguments, "leftPath");
        Path rightPath = JsonUtils.getPath(arguments, "rightPath");
        if (rightPath == null) {
            rightPath = leftPath;
        }
        if (!Files.exists(leftPath)) {
            throw new IllegalArgumentException("leftPath not found: " + leftPath);
        }
        if (!Files.exists(rightPath)) {
            throw new IllegalArgumentException("rightPath not found: " + rightPath);
        }

        JsonObject leftArgs = arguments.deepCopy();
        JsonObject rightArgs = arguments.deepCopy();
        leftArgs.addProperty("path", leftPath.toString());
        rightArgs.addProperty("path", rightPath.toString());
        leftArgs.addProperty("engine", JsonUtils.getString(arguments, "leftEngine", AUTO));
        rightArgs.addProperty("engine", JsonUtils.getString(arguments, "rightEngine", AUTO));

        String leftClassName = JsonUtils.getString(arguments, "leftClassName", JsonUtils.getString(arguments, "className", null));
        String rightClassName = JsonUtils.getString(arguments, "rightClassName", leftClassName);

        DecompilerOptions leftOptions = DecompilerOptions.fromArguments(leftArgs, AUTO);
        DecompilerOptions rightOptions = DecompilerOptions.fromArguments(rightArgs, AUTO);
        try (InputContainer left = InputContainers.open(leftPath, leftOptions.releaseVersion());
             InputContainer right = InputContainers.open(rightPath, rightOptions.releaseVersion())) {
            DecompilationOutcome leftOutcome = decompile(left, leftClassName != null ? leftClassName : defaultClass(left), leftOptions);
            DecompilationOutcome rightOutcome = decompile(right, rightClassName != null ? rightClassName : defaultClass(right), rightOptions);

            JsonObject structured = new JsonObject();
            structured.addProperty("leftEngine", leftOutcome.engineUsed());
            structured.addProperty("rightEngine", rightOutcome.engineUsed());
            structured.addProperty("leftClass", leftOutcome.internalName());
            structured.addProperty("rightClass", rightOutcome.internalName());
            structured.addProperty("leftSource", leftOutcome.result().getDecompiledOutput());
            structured.addProperty("rightSource", rightOutcome.result().getDecompiledOutput());
            structured.add("diff", diffJson(leftOutcome.result().getDecompiledOutput(), rightOutcome.result().getDecompiledOutput()));

            StringBuilder text = new StringBuilder();
            text.append("Compare class\n");
            text.append("Left: ").append(leftOutcome.internalName().replace('/', '.')).append(" [").append(leftOutcome.engineUsed()).append("]\n");
            text.append("Right: ").append(rightOutcome.internalName().replace('/', '.')).append(" [").append(rightOutcome.engineUsed()).append("]\n");
            JsonArray diff = structured.getAsJsonArray("diff");
            text.append("Differing lines: ").append(diff.size()).append('\n');
            for (int i = 0; i < Math.min(diff.size(), 20); i++) {
                JsonObject line = diff.get(i).getAsJsonObject();
                text.append("- line ").append(line.get("line").getAsInt()).append('\n');
            }
            return ToolResults.structured(text.toString().trim(), structured);
        }
    }

    private static JsonArray diffJson(String left, String right) {
        JsonArray diff = new JsonArray();
        String[] leftLines = left.split("\\R", -1);
        String[] rightLines = right.split("\\R", -1);
        int max = Math.max(leftLines.length, rightLines.length);
        for (int i = 0; i < max; i++) {
            String l = i < leftLines.length ? leftLines[i] : "";
            String r = i < rightLines.length ? rightLines[i] : "";
            if (!l.equals(r)) {
                JsonObject line = new JsonObject();
                line.addProperty("line", i + 1);
                line.addProperty("left", l);
                line.addProperty("right", r);
                diff.add(line);
            }
        }
        return diff;
    }

    private static String defaultClass(InputContainer container) {
        var defaultClass = container.defaultClass();
        if (defaultClass == null) {
            throw new IllegalArgumentException("className is required when the input contains multiple classes");
        }
        return defaultClass.internalName();
    }
}
