package server;

import cli.CliMode;
import model.*;
import support.StdoutGuard;
import tools.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonIOException;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.JsonReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * MCP (Model Context Protocol) Server Main Class
 * Implements JSON-RPC 2.0 protocol for Java decompilation service
 */
public class MCPServer {
    static {
        configureLoggingDefaults();
    }

    private static final Logger logger = LoggerFactory.getLogger(MCPServer.class);
    private static final String VERSION = support.VersionSupport.readVersion();
    private static final String DEFAULT_PROTOCOL_VERSION = "2025-11-25";
    private static final int MAX_MESSAGE_BYTES = 10 * 1024 * 1024; // 10 MB
    private static final Set<String> SUPPORTED_PROTOCOL_VERSIONS = Set.of(
            "2025-11-25",
            "2025-06-18",
            "2025-03-26",
            "2024-11-05"
    );
    private final Gson gson;
    private final Map<String, MCPTool> tools;
    private boolean running = true;
    private boolean initializeResponded;
    private boolean initialized;
    private boolean shutdownRequested;
    private String protocolVersion = DEFAULT_PROTOCOL_VERSION;

    public MCPServer() {
        this.gson = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
        this.tools = new LinkedHashMap<>();
        registerTools();
    }

    /**
     * Register all available tools
     */
    private void registerTools() {
        // Core decompilation tools
        tools.put("decompile_class", new DecompileClassTool());
        tools.put("decompile_advanced", new AdvancedDecompileTool());

        // Batch processing tools
        tools.put("batch_decompile", new BatchDecompileTool());
        tools.put("batch_decompile_jars", new BatchDecompileJarsTool());
        tools.put("decompile_directory", new DecompileDirectoryTool());
        tools.put("analyze_directory", new AnalyzeDirectoryTool());

        // JAR analysis tools
        tools.put("decompile_jar", new DecompileJarTool());
        tools.put("list_classes", new ListClassesTool());
        tools.put("search_in_jar", new SearchInJarTool());
        tools.put("compare_jars", new CompareJarsTool());
        tools.put("compare_class", new CompareClassTool());
        tools.put("compare_jd_core", new CompareJdCoreTool());
        tools.put("save_all_sources", new SaveAllSourcesTool());
        tools.put("type_lookup", new TypeLookupTool());
        tools.put("type_hierarchy", new TypeHierarchyTool());
        tools.put("find_references", new FindReferencesTool());
        tools.put("method_overrides", new MethodOverridesTool());
        tools.put("resolve_symbol", new ResolveSymbolTool());
        tools.put("resolve_stacktrace", new ResolveStacktraceTool());
        tools.put("analyze_log", new ResolveStacktraceTool());
        tools.put("source_lookup", new SourceLookupTool());
        tools.put("class_metadata", new ClassMetadataTool());
        tools.put("source_quality_report", new SourceQualityReportTool());
        tools.put("build_skeleton", new BuildSkeletonTool());
        tools.put("list_dependencies", new ListDependenciesTool());
        tools.put("compiler_diagnostics", new CompilerDiagnosticsTool());
        tools.put("remove_unnecessary_casts", new RemoveUnnecessaryCastsTool());
        tools.put("list_engines", new ListEnginesTool());
        tools.put("describe_engine_options", new DescribeEngineOptionsTool());

        // Bytecode tools
        tools.put("show_bytecode", new ShowBytecodeTool());
        tools.put("call_chain", new CallChainTool());
        tools.put("show_cfg", new ShowCfgTool());

        logger.info("Registered {} tools", tools.size());
    }

    /**
     * Start the server
     */
    public void start() {
        serve(System.in, System.out);
    }

    public void serve(InputStream inputStream, OutputStream outputStream) {
        logger.info("JD MCP Duo v{} starting...", VERSION);
        logger.info("Using stdio for communication");
        running = true;
        shutdownRequested = false;
        initializeResponded = false;
        initialized = false;
        protocolVersion = DEFAULT_PROTOCOL_VERSION;

        try (StdoutGuard.Scope ignored = StdoutGuard.silenceByDefault();
             BufferedInputStream reader = new BufferedInputStream(inputStream)) {

            while (running) {
                String payload = readMessage(reader);
                if (payload == null) {
                    logger.info("Input stream closed, shutting down server");
                    break;
                }

                payload = payload.trim();
                if (payload.isEmpty()) {
                    continue;
                }

                try {
                    JsonElement message = parseStrictJson(payload);
                    if (!message.isJsonObject()) {
                        writeMessage(outputStream, gson.toJson(createErrorResponse(JsonNull.INSTANCE, -32600,
                                "Invalid Request")));
                        continue;
                    }
                    JsonObject response = handleRequest(message.getAsJsonObject());

                    if (response != null) {
                        writeMessage(outputStream, gson.toJson(response));
                    }
                } catch (JsonSyntaxException | JsonIOException e) {
                    logger.warn("Invalid JSON-RPC payload: {}", e.getMessage());
                    writeMessage(outputStream, gson.toJson(createErrorResponse(JsonNull.INSTANCE, -32700,
                            "Parse error")));
                } catch (Exception e) {
                    logger.error("Error processing request", e);
                    JsonObject errorResponse = createErrorResponse(null, -32603,
                            "Internal error: " + e.getMessage());
                    writeMessage(outputStream, gson.toJson(errorResponse));
                }
            }
        } catch (IOException e) {
            logger.error("IO error", e);
        }

        logger.info("Server stopped");
    }

    /**
     * Handle JSON-RPC request
     */
    private JsonObject handleRequest(JsonObject request) {
        if (request == null) {
            return createErrorResponse(JsonNull.INSTANCE, -32600, "Invalid Request");
        }

        boolean idMemberPresent = request.has("id");
        JsonElement id = idMemberPresent ? request.get("id") : null;
        boolean hasRequestId = idMemberPresent && id != null && !id.isJsonNull();
        if (idMemberPresent && !isValidRequestId(id)) {
            return createErrorResponse(JsonNull.INSTANCE, -32600, "Invalid Request: id must be a string or number");
        }
        JsonElement errorId = idMemberPresent ? id : JsonNull.INSTANCE;
        JsonElement jsonrpcElement = request.get("jsonrpc");
        if (jsonrpcElement == null
                || !jsonrpcElement.isJsonPrimitive()
                || !jsonrpcElement.getAsJsonPrimitive().isString()
                || !"2.0".equals(jsonrpcElement.getAsString())) {
            return createErrorResponse(errorId, -32600, "Invalid Request: jsonrpc must be \"2.0\"");
        }
        JsonElement methodElement = request.get("method");

        if (methodElement == null || !methodElement.isJsonPrimitive()) {
            return createErrorResponse(errorId, -32600, "Invalid Request: missing method");
        }
        JsonPrimitive methodPrimitive = methodElement.getAsJsonPrimitive();
        if (!methodPrimitive.isString()) {
            return createErrorResponse(errorId, -32600, "Invalid Request: method must be a string");
        }
        String method = methodPrimitive.getAsString();
        if (method == null || method.isBlank()) {
            return createErrorResponse(errorId, -32600, "Invalid Request: missing method");
        }

        if (!hasRequestId && !isNotificationMethod(method)) {
            logger.warn("Ignoring MCP notification for request-only or unknown method: {}", method);
            return null;
        }
        if (!hasRequestId && isIgnorableNotification(method)) {
            logger.debug("Ignoring MCP notification: {}", method);
            return null;
        }

        if (shutdownRequested
                && !"shutdown".equals(method)
                && !"exit".equals(method)
                && !"notifications/exit".equals(method)) {
            return createErrorResponse(id, -32000, "Server is shutting down");
        }

        if (!initializeResponded && !isPreInitializeMethod(method)) {
            return createErrorResponse(id, -32002, "Server has not been initialized");
        }
        if (initializeResponded && !initialized && !isInitializeCompletionMethod(method)) {
            return createErrorResponse(id, -32002, "Client has not completed MCP initialization");
        }

        logger.debug("Received request: method={}, id={}", method, id);

        return switch (method) {
            case "initialize" -> handleInitialize(request, id);
            case "notifications/initialized" -> handleInitialized();
            case "tools/list" -> handleToolsList(request, id);
            case "tools/call" -> handleToolsCall(request, id);
            case "ping" -> createSuccessResponse(id, new JsonObject());
            case "shutdown" -> handleShutdown(request, id);
            case "exit", "notifications/exit" -> handleExit();
            default -> createErrorResponse(id, -32601, "Method not found: " + method);
        };
    }

    /**
     * Handle initialize request
     */
    private JsonObject handleInitialize(JsonObject request, JsonElement id) {
        if (initializeResponded) {
            return createErrorResponse(id, -32600, "Server is already initialized");
        }
        JsonObject params = request.has("params") && request.get("params").isJsonObject()
                ? request.getAsJsonObject("params")
                : new JsonObject();
        String requestedProtocolVersion = params.has("protocolVersion") && !params.get("protocolVersion").isJsonNull()
                ? params.get("protocolVersion").getAsString()
                : DEFAULT_PROTOCOL_VERSION;
        protocolVersion = SUPPORTED_PROTOCOL_VERSIONS.contains(requestedProtocolVersion)
                ? requestedProtocolVersion
                : DEFAULT_PROTOCOL_VERSION;
        initializeResponded = true;

        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", protocolVersion);
        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", "jd-mcp-duo");
        serverInfo.addProperty("version", VERSION);
        result.add("serverInfo", serverInfo);
        
        JsonObject capabilities = new JsonObject();
        JsonObject toolsCapability = new JsonObject();
        toolsCapability.addProperty("listChanged", false);
        capabilities.add("tools", toolsCapability);
        result.add("capabilities", capabilities);

        logger.info("Client initialized");
        return createSuccessResponse(id, result);
    }

    private JsonObject handleInitialized() {
        if (!initializeResponded) {
            logger.warn("Ignoring initialized notification before initialize request");
            return null;
        }
        initialized = true;
        logger.info("Client sent initialized notification");
        return null;
    }

    /**
     * Handle tools list request
     */
    private JsonObject handleToolsList(JsonObject request, JsonElement id) {
        List<Map<String, Object>> toolsList = new ArrayList<>();
        
        for (Map.Entry<String, MCPTool> entry : tools.entrySet()) {
            Map<String, Object> toolInfo = new HashMap<>();
            toolInfo.put("name", entry.getKey());
            toolInfo.put("description", entry.getValue().getDescription());
            toolInfo.put("inputSchema", entry.getValue().getInputSchema());
            if (entry.getValue().getOutputSchema() != null) {
                toolInfo.put("outputSchema", entry.getValue().getOutputSchema());
            }
            toolsList.add(toolInfo);
        }

        JsonObject result = new JsonObject();
        result.add("tools", gson.toJsonTree(toolsList));
        
        return createSuccessResponse(id, result);
    }

    /**
     * Handle tool call request
     */
    private JsonObject handleToolsCall(JsonObject request, JsonElement id) {
        if (!request.has("params") || !request.get("params").isJsonObject()) {
            return createErrorResponse(id, -32602, "Invalid params");
        }
        JsonObject params = request.getAsJsonObject("params");

        JsonElement toolNameElement = params.get("name");
        if (toolNameElement == null
                || !toolNameElement.isJsonPrimitive()
                || !toolNameElement.getAsJsonPrimitive().isString()
                || toolNameElement.getAsString().isBlank()) {
            return createErrorResponse(id, -32602, "Missing tool name");
        }
        String toolName = toolNameElement.getAsString();
        JsonElement argumentsElement = params.get("arguments");
        if (argumentsElement != null && !argumentsElement.isJsonNull() && !argumentsElement.isJsonObject()) {
            return createErrorResponse(id, -32602, "Tool arguments must be an object");
        }
        JsonObject arguments = argumentsElement != null && argumentsElement.isJsonObject()
                ? argumentsElement.getAsJsonObject()
                : new JsonObject();

        MCPTool tool = tools.get(toolName);
        if (tool == null) {
            return createErrorResponse(id, -32602, "Unknown tool: " + toolName);
        }

        logger.info("Calling tool: {}", toolName);

        try {
            ToolResult result = StdoutGuard.callSilenced(() -> tool.execute(arguments));
            JsonObject resultObj = new JsonObject();
            JsonArray content = new JsonArray();
            JsonObject textContent = new JsonObject();
            textContent.addProperty("type", "text");
            textContent.addProperty("text", result.text());
            content.add(textContent);
            resultObj.add("content", content);
            if (result.hasStructuredData()) {
                resultObj.add("structuredContent", result.structuredData());
            }
            resultObj.addProperty("isError", result.isError());
            
            return createSuccessResponse(id, resultObj);
        } catch (Exception e) {
            logger.error("Tool execution failed: {}", toolName, e);
            JsonObject resultObj = new JsonObject();
            JsonArray content = new JsonArray();
            JsonObject textContent = new JsonObject();
            textContent.addProperty("type", "text");
            textContent.addProperty("text", "Tool execution failed: " + e.getMessage());
            content.add(textContent);
            resultObj.add("content", content);
            resultObj.addProperty("isError", true);
            return createSuccessResponse(id, resultObj);
        }
    }

    /**
     * Handle shutdown request
     */
    private JsonObject handleShutdown(JsonObject request, JsonElement id) {
        logger.info("Received shutdown request");
        shutdownRequested = true;
        return createSuccessResponse(id, new JsonObject());
    }

    private JsonObject handleExit() {
        logger.info("Received exit notification");
        running = false;
        return null;
    }

    /**
     * Create success response
     */
    private JsonObject createSuccessResponse(JsonElement id, JsonObject result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("result", result);
        if (id != null) {
            response.add("id", id);
        }
        return response;
    }

    /**
     * Create error response
     */
    private JsonObject createErrorResponse(JsonElement id, int code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);

        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("error", error);
        if (id != null) {
            response.add("id", id);
        }
        return response;
    }

    /**
     * Get tools map (for CLI mode)
     */
    public Map<String, MCPTool> getTools() {
        return tools;
    }

    private static void writeMessage(OutputStream outputStream, String json) throws IOException {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        outputStream.write(payload);
        outputStream.write('\n');
        outputStream.flush();
    }

    private static JsonElement parseStrictJson(String payload) throws IOException {
        rejectClearlyInvalidJsonStart(payload);
        try (JsonReader jsonReader = new JsonReader(new StringReader(payload))) {
            jsonReader.setLenient(false);
            return JsonParser.parseReader(jsonReader);
        }
    }

    private static void rejectClearlyInvalidJsonStart(String payload) {
        String trimmed = payload.trim();
        if (trimmed.isEmpty()) {
            throw new JsonSyntaxException("Empty JSON-RPC payload");
        }
        char first = trimmed.charAt(0);
        if ("{[\"tfn-0123456789".indexOf(first) < 0) {
            throw new JsonSyntaxException("Invalid JSON-RPC payload");
        }
        if (first == 't' && !"true".equals(trimmed)
                || first == 'f' && !"false".equals(trimmed)
                || first == 'n' && !"null".equals(trimmed)) {
            throw new JsonSyntaxException("Invalid JSON literal");
        }
    }

    private static String readMessage(InputStream inputStream) throws IOException {
        String line;
        do {
            line = readUtf8Line(inputStream);
            if (line == null) {
                return null;
            }
        } while (line.isBlank());

        if (!line.startsWith("{") && !line.startsWith("[") && line.contains(":")) {
            Integer contentLength = parseContentLength(line);
            while (true) {
                String header = readUtf8Line(inputStream);
                if (header == null) {
                    throw new EOFException("Unexpected EOF while reading MCP headers");
                }
                if (header.isEmpty()) {
                    break;
                }
                Integer candidate = parseContentLength(header);
                if (candidate != null) {
                    contentLength = candidate;
                }
            }
            if (contentLength == null || contentLength < 0) {
                throw new IOException("Missing Content-Length header");
            }
            if (contentLength > MAX_MESSAGE_BYTES) {
                throw new IOException("Content-Length exceeds maximum of " + MAX_MESSAGE_BYTES + " bytes");
            }
            byte[] payload = inputStream.readNBytes(contentLength);
            if (payload.length != contentLength) {
                throw new EOFException("Unexpected EOF while reading MCP payload");
            }
            return new String(payload, StandardCharsets.UTF_8);
        }

        return line;
    }

    private static String readUtf8Line(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (true) {
            int next = inputStream.read();
            if (next == -1) {
                if (buffer.size() == 0) {
                    return null;
                }
                break;
            }
            if (next == '\n') {
                break;
            }
            if (next != '\r') {
                buffer.write(next);
            }
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static boolean isPreInitializeMethod(String method) {
        return "initialize".equals(method)
                || "ping".equals(method)
                || "exit".equals(method)
                || "notifications/exit".equals(method);
    }

    private static boolean isNotificationMethod(String method) {
        return "notifications/initialized".equals(method)
                || "notifications/cancelled".equals(method)
                || "notifications/progress".equals(method)
                || "notifications/roots/list_changed".equals(method)
                || "exit".equals(method)
                || "notifications/exit".equals(method);
    }

    private static boolean isIgnorableNotification(String method) {
        return "notifications/cancelled".equals(method)
                || "notifications/progress".equals(method)
                || "notifications/roots/list_changed".equals(method);
    }

    private static boolean isValidRequestId(JsonElement id) {
        if (id == null || id.isJsonNull() || !id.isJsonPrimitive()) {
            return false;
        }
        JsonPrimitive primitive = id.getAsJsonPrimitive();
        return primitive.isString() || primitive.isNumber();
    }

    private static boolean isInitializeCompletionMethod(String method) {
        return "initialize".equals(method)
                || "notifications/initialized".equals(method)
                || "ping".equals(method)
                || "exit".equals(method)
                || "notifications/exit".equals(method);
    }

    private static Integer parseContentLength(String headerLine) {
        int separator = headerLine.indexOf(':');
        if (separator < 0) {
            return null;
        }
        String name = headerLine.substring(0, separator).trim();
        if (!"content-length".equalsIgnoreCase(name)) {
            return null;
        }
        try {
            return Integer.parseInt(headerLine.substring(separator + 1).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void configureLoggingDefaults() {
        if (System.getProperty("org.slf4j.simpleLogger.logFile") == null) {
            System.setProperty("org.slf4j.simpleLogger.logFile", "System.err");
        }
        if (System.getProperty("org.slf4j.simpleLogger.defaultLogLevel") == null) {
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        }
    }

    public static void main(String[] args) {
        // Keep logs on stderr so MCP JSON-RPC frames on stdout stay clean.
        configureLoggingDefaults();

        MCPServer server = new MCPServer();

        if (args.length == 0) {
            System.err.println("jd-mcp-duo v" + VERSION + " — MCP server mode");
            System.err.println("Listening on stdin for JSON-RPC 2.0 frames...");
        }

        // Select the runtime mode.
        if (args.length > 0) {
            // CLI mode.
            CliMode cliMode = new CliMode(server.getTools());
            cliMode.execute(args);
        } else {
            // MCP server mode.
            server.start();
        }
    }
}
