package cli;

import model.MCPTool;
import model.ToolResult;
import support.JsonUtils;
import support.ProgressReporter;
import support.StdoutGuard;
import support.VersionSupport;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * CLI mode handler.
 */
public class CliMode {
    private static final Logger logger = LoggerFactory.getLogger(CliMode.class);

    private final Map<String, MCPTool> tools;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public CliMode(Map<String, MCPTool> tools) {
        this.tools = tools;
    }

    /**
     * Execute a CLI command.
     */
    public void execute(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    public int run(String[] args, java.io.PrintStream out, java.io.PrintStream err) {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")) {
            printHelp(out);
            return 0;
        }
        if (args[0].equals("--version") || args[0].equals("-v")) {
            out.println("jd-mcp-duo v" + VersionSupport.readVersion());
            return 0;
        }

        String toolName = args[0];
        boolean jsonOutput = false;

        // Validate the tool name.
        if (!tools.containsKey(toolName)) {
            err.println("Error: unknown tool name: " + toolName);
            err.println();
            printAvailableTools(out);
            return 1;
        }

        for (int i = 1; i < args.length; i++) {
            if ("--json".equals(args[i])) {
                jsonOutput = true;
            }
            if ("--help".equals(args[i]) || "-h".equals(args[i])) {
                printToolHelp(toolName, out);
                return 0;
            }
        }

        // Parse arguments.
        JsonObject arguments = parseArguments(args);

        // Execute the tool.
        MCPTool tool = tools.get(toolName);
        ProgressReporter reporter = new ProgressReporter(null, toolName);
        try {
            logger.info("Running tool: {}", toolName);
            reporter.report(0, 0);
            ToolResult result = StdoutGuard.callSilenced(() -> tool.execute(arguments, reporter));
            reporter.done();
            long elapsed = reporter.elapsedMillis();
            String elapsedStr = elapsed >= 1000 ? String.format("%.1fs", elapsed / 1000.0) : elapsed + "ms";
            err.printf("[jd-mcp-duo] %s completed (%s)%n", toolName, elapsedStr);
            if (jsonOutput) {
                JsonObject json = new JsonObject();
                json.addProperty("text", result.text());
                json.add("structuredData", result.structuredData());
                json.addProperty("isError", result.isError());
                out.println(gson.toJson(json));
            } else {
                out.println(result.text());
            }
            return result.isError() ? 1 : 0;
        } catch (Exception e) {
            reporter.done();
            err.println("Execution failed: " + e.getMessage());
            err.println("Usage: java -Xss10m -jar " + jarName() + " " + MCPTool.buildUsageExample(toolName, tool.getInputSchema()));
            err.println("See: java -Xss10m -jar " + jarName() + " " + toolName + " --help");
            logger.error("Tool execution failed", e);
            return 1;
        }
    }

    /**
     * Parse command-line arguments into a JsonObject.
     * Format: --key=value or --key value
     */
    private JsonObject parseArguments(String[] args) {
        JsonObject arguments = new JsonObject();

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if ("--json".equals(arg)) {
                continue;
            }

            if (arg.startsWith("--")) {
                String key;
                String value;

                if (arg.contains("=")) {
                    // Format: --key=value
                    int equalIndex = arg.indexOf('=');
                    key = arg.substring(2, equalIndex);
                    value = arg.substring(equalIndex + 1);
                } else {
                    // Format: --key value
                    key = arg.substring(2);
                    if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        value = args[i + 1];
                        i++; // Skip the next argument.
                    } else {
                        // Boolean flag.
                        value = "true";
                    }
                }

                JsonElement parsedValue = JsonUtils.parseCliValue(value);
                if (arguments.has(key)) {
                    JsonArray array = arguments.get(key).isJsonArray()
                            ? arguments.getAsJsonArray(key)
                            : new JsonArray();
                    if (!arguments.get(key).isJsonArray()) {
                        array.add(arguments.get(key));
                    }
                    array.add(parsedValue);
                    arguments.add(key, array);
                } else {
                    arguments.add(key, parsedValue);
                }
            }
        }

        return arguments;
    }

    /**
     * Print general help.
     */
    private void printHelp(java.io.PrintStream out) {
        out.println("===================================================================");
        out.println("  JD MCP Duo v" + VersionSupport.readVersion() + " - Java Decompiler Toolkit");
        out.println("===================================================================");
        out.println();
        out.println("Usage:");
        out.println("  1. MCP server mode:");
        out.println("     java -Xss10m -jar " + jarName() + "");
        out.println();
        out.println("  2. CLI mode:");
        out.println("     java -Xss10m -jar " + jarName() + " <tool-name> [options] [--json]");
        out.println();
        out.println("Examples:");
        out.println("  # Decompile a single class file");
        out.println("  java -Xss10m -jar " + jarName() + " decompile_class --path=/path/to/MyClass.class");
        out.println();
        out.println("  # Decompile a class from a JAR");
        out.println("  java -Xss10m -jar " + jarName() + " decompile_class --path=/path/to/app.jar --className=com.example.Main");
        out.println();
        out.println("  # Decompile all classes from a JAR to a directory using the default auto engine");
        out.println("  java -Xss10m -jar " + jarName() + " save_all_sources --path=/path/to/app.jar --output=/path/to/out");
        out.println();
        out.println("  # Decompile all classes from a JAR to a directory using a specific engine");
        out.println("  java -Xss10m -jar " + jarName() + " save_all_sources --path=/path/to/app.jar --output=/path/to/out --engine=jadx");
        out.println();
        out.println("  # Decompile every supported file under a directory to another directory using the default auto engine");
        out.println("  java -Xss10m -jar " + jarName() + " decompile_directory --path=/path/to/input --outputDir=/path/to/out");
        out.println();
        out.println("  # Decompile every supported file under a directory to another directory using a specific engine");
        out.println("  java -Xss10m -jar " + jarName() + " decompile_directory --path=/path/to/input --outputDir=/path/to/out --engine=jadx");
        out.println();
        out.println("  # List supported decompiler engines");
        out.println("  java -Xss10m -jar " + jarName() + " list_engines");
        out.println();
        out.println("  # Decompile with Vineflower (best modern Java accuracy)");
        out.println("  java -Xss10m -jar " + jarName() + " decompile_class --path=/path/to/app.jar --className=com.example.Main --engine=vineflower");
        out.println();
        out.println("  # Compare JD-Core v0 and JD-Core v1 output for one class");
        out.println("  java -Xss10m -jar " + jarName() + " compare_jd_core --path=/path/to/app.jar --className=com.example.Main");
        out.println();
        out.println("  # List all classes in a JAR");
        out.println("  java -Xss10m -jar " + jarName() + " list_classes --path=/path/to/app.jar");
        out.println();
        out.println("  # Search");
        out.println("  java -Xss10m -jar " + jarName() + " search_in_jar --path=/path/to/app.jar --query=MyClass");
        out.println();
        printAvailableTools(out);
    }

    private void printToolHelp(String toolName, java.io.PrintStream out) {
        MCPTool tool = tools.get(toolName);
        out.println(toolName);
        out.println("=".repeat(toolName.length()));
        out.println(tool.getDescription());
        out.println();
        out.println("Input schema:");
        out.println(gson.toJson(tool.getInputSchema()));
        if (tool.getOutputSchema() != null) {
            out.println();
            out.println("Output schema:");
            out.println(gson.toJson(tool.getOutputSchema()));
        }
    }

    /**
     * Print the available tools.
     */
    private void printAvailableTools(java.io.PrintStream out) {
        out.println("Available tools:");
        out.println();

        int index = 1;
        for (Map.Entry<String, MCPTool> entry : tools.entrySet()) {
            out.printf("  %d. %s%n", index++, entry.getKey());
            out.printf("     %s%n", entry.getValue().getDescription());
            out.println();
        }

        out.println("Use 'java -Xss10m -jar " + jarName() + " <tool-name> --help' to inspect a tool's full schema.");
    }

    private static String jarName() {
        return "jd-mcp-duo.jar";
    }
}
