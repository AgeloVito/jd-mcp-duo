package tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import decompile.DecompilerEngines;
import decompile.DecompilerOptions;
import model.MCPTool;
import model.ToolResult;
import org.eclipse.jdt.core.dom.CompilationUnit;
import support.JdtParserSupport;
import support.SchemaSupport;
import support.ToolResults;

public class CompilerDiagnosticsTool implements MCPTool {
    @Override
    public String getDescription() {
        return "Analyze Java source or decompiled output with the Eclipse JDT compiler and return errors and warnings.";
    }

    @Override
    public JsonObject getInputSchema() {
        JsonObject schema = SchemaSupport.objectSchema();
        JsonObject properties = SchemaSupport.properties(schema);
        SchemaSupport.addString(properties, "path", "Java source file, class file, archive, or directory");
        SchemaSupport.addString(properties, "className", "Class name when the input contains multiple classes");
        SchemaSupport.addString(properties, "engine", "Decompiler engine when path is not a .java file");
        SchemaSupport.addString(properties, "profile", "fast, accurate, or debuggable");
        SchemaSupport.addInteger(properties, "attemptTimeoutMillis", "Per-engine attempt timeout in milliseconds; 0 disables timeout", (int) decompile.DecompilerOptions.DEFAULT_ATTEMPT_TIMEOUT_MILLIS);
        SchemaSupport.addBoolean(properties, "lineNumbers", "Include line-number metadata when decompiling first", false);
        SchemaSupport.addBoolean(properties, "advancedLookup", "Search sibling archives for dependency resolution; JDK modules are included by default", false);
        SchemaSupport.addStringOrArray(properties, "classpath", "Additional classpath entries");
        SchemaSupport.require(schema, "path");
        return schema;
    }

    @Override
    public ToolResult execute(JsonObject arguments) throws Exception {
        DecompilerOptions options = DecompilerOptions.fromArguments(arguments, DecompilerEngines.AUTO);
        JdtParserSupport.SourceContext context = JdtParserSupport.resolve(arguments, options);
        CompilationUnit unit = JdtParserSupport.parse(context, true);

        JsonArray errors = new JsonArray();
        JsonArray warnings = new JsonArray();
        for (JsonObject diagnostic : JdtParserSupport.diagnostics(unit)) {
            if (diagnostic.get("isError").getAsBoolean()) {
                errors.add(diagnostic);
            } else if (diagnostic.get("isWarning").getAsBoolean()) {
                warnings.add(diagnostic);
            }
        }

        JsonObject structured = new JsonObject();
        structured.addProperty("logicalName", context.logicalName());
        structured.addProperty("errorCount", errors.size());
        structured.addProperty("warningCount", warnings.size());
        structured.add("errors", errors);
        structured.add("warnings", warnings);

        StringBuilder text = new StringBuilder();
        text.append("Compiler diagnostics for ").append(context.logicalName()).append('\n');
        text.append("Errors: ").append(errors.size()).append('\n');
        text.append("Warnings: ").append(warnings.size()).append('\n');
        return ToolResults.structured(text.toString().trim(), structured);
    }
}
