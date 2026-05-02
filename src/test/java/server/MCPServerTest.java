package server;

import testing.TestFixtures;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MCPServerTest {
    @Test
    void testStrictMcpTranscript(@TempDir Path tempDir) throws Exception {
        Path classesDir = TestFixtures.compileSources(tempDir, Map.of(
                "demo/App.java", "package demo; public class App { public String hi(){ return \"hi\"; } }"
        ));
        Path jarPath = TestFixtures.createJar(tempDir.resolve("demo.jar"), classesDir);

        String input = jsonLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"clientInfo\":{\"name\":\"test\",\"version\":\"1.0.0\"}}}")
                + jsonLine("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}")
                + jsonLine("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}")
                + jsonLine("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"list_classes\",\"arguments\":{\"path\":\"" + jarPath.toString().replace("\\", "\\\\") + "\"}}}")
                + jsonLine("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"shutdown\",\"params\":{}}")
                + jsonLine("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"ping\",\"params\":{}}")
                + jsonLine("{\"jsonrpc\":\"2.0\",\"method\":\"exit\",\"params\":{}}");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new MCPServer().serve(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output);

        String outputText = output.toString(StandardCharsets.UTF_8);
        assertFalse(outputText.contains("Content-Length:"));
        List<JsonObject> responses = parseJsonLines(outputText);
        assertEquals(5, responses.size());

        JsonObject initialize = responses.get(0);
        assertTrue(initialize.getAsJsonObject("result").has("serverInfo"));
        assertEquals("2025-11-25", initialize.getAsJsonObject("result").get("protocolVersion").getAsString());
        assertFalse(initialize.getAsJsonObject("result").getAsJsonObject("capabilities").getAsJsonObject("tools").isEmpty());

        JsonObject toolsList = responses.get(1);
        JsonArray tools = toolsList.getAsJsonObject("result").getAsJsonArray("tools");
        assertTrue(tools.asList().stream().anyMatch(item -> item.getAsJsonObject().get("name").getAsString().equals("call_chain")));
        assertTrue(tools.asList().stream().anyMatch(item -> item.getAsJsonObject().get("name").getAsString().equals("compiler_diagnostics")));
        assertTrue(tools.asList().stream().anyMatch(item -> item.getAsJsonObject().get("name").getAsString().equals("remove_unnecessary_casts")));

        JsonObject toolCall = responses.get(2);
        assertFalse(toolCall.getAsJsonObject("result").get("isError").getAsBoolean());
        assertTrue(toolCall.getAsJsonObject("result").getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString().contains("demo.App"));

        JsonObject afterShutdown = responses.get(4);
        assertTrue(afterShutdown.has("error"));
        assertEquals(-32000, afterShutdown.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void testUnknownNotificationDoesNotReceiveResponse() throws Exception {
        String input = jsonLine("{\"jsonrpc\":\"2.0\",\"method\":\"unknown/notification\",\"params\":{}}")
                + jsonLine("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":99,\"reason\":\"test\"}}")
                + jsonLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}")
                + jsonLine("{\"jsonrpc\":\"2.0\",\"method\":\"exit\",\"params\":{}}");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new MCPServer().serve(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output);

        List<JsonObject> responses = parseJsonLines(output.toString(StandardCharsets.UTF_8));
        assertEquals(1, responses.size());
        assertEquals(1, responses.get(0).get("id").getAsInt());
    }

    @Test
    void testToolsAreRejectedBeforeInitializedNotification() throws Exception {
        String input = jsonLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}")
                + jsonLine("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}")
                + jsonLine("{\"jsonrpc\":\"2.0\",\"method\":\"exit\",\"params\":{}}");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new MCPServer().serve(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output);

        List<JsonObject> responses = parseJsonLines(output.toString(StandardCharsets.UTF_8));
        assertEquals(2, responses.size());
        assertTrue(responses.get(1).has("error"));
        assertEquals(-32002, responses.get(1).getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void testInvalidToolArgumentsReturnProtocolErrorWithRequestId() throws Exception {
        String input = jsonLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}")
                + jsonLine("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}")
                + jsonLine("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"list_classes\",\"arguments\":[]}}")
                + jsonLine("{\"jsonrpc\":\"2.0\",\"method\":\"exit\",\"params\":{}}");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new MCPServer().serve(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output);

        List<JsonObject> responses = parseJsonLines(output.toString(StandardCharsets.UTF_8));
        assertEquals(2, responses.size());
        assertEquals(2, responses.get(1).get("id").getAsInt());
        assertEquals(-32602, responses.get(1).getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void testRequestOnlyNotificationDoesNotExecuteTool(@TempDir Path tempDir) throws Exception {
        Path classesDir = TestFixtures.compileSources(tempDir, Map.of(
                "demo/App.java", "package demo; public class App { public String hi(){ return \"hi\"; } }"
        ));
        Path outputFile = tempDir.resolve("out/App.java");

        String input = jsonLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}")
                + jsonLine("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}")
                + jsonLine("{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":\"decompile_class\",\"arguments\":{\"path\":\""
                + jsonEscape(classesDir.resolve("demo/App.class").toString())
                + "\",\"output\":\""
                + jsonEscape(outputFile.toString())
                + "\"}}}")
                + jsonLine("{\"jsonrpc\":\"2.0\",\"method\":\"exit\",\"params\":{}}");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new MCPServer().serve(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output);

        List<JsonObject> responses = parseJsonLines(output.toString(StandardCharsets.UTF_8));
        assertEquals(1, responses.size());
        assertEquals(1, responses.get(0).get("id").getAsInt());
        assertFalse(Files.exists(outputFile));
    }

    @Test
    void testInvalidRequestIdIsRejected() throws Exception {
        String input = jsonLine("{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"initialize\",\"params\":{}}")
                + jsonLine("{\"jsonrpc\":\"2.0\",\"method\":\"exit\",\"params\":{}}");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new MCPServer().serve(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output);

        List<JsonObject> responses = parseJsonLines(output.toString(StandardCharsets.UTF_8));
        assertEquals(1, responses.size());
        assertTrue(responses.get(0).has("error"));
        assertTrue(responses.get(0).get("id").isJsonNull());
        assertEquals(-32600, responses.get(0).getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void testInvalidJsonAndJsonRpcVersionReturnProtocolErrors() throws Exception {
        String parseErrorInput = "not-json\n"
                + jsonLine("{\"jsonrpc\":\"2.0\",\"method\":\"exit\",\"params\":{}}");
        ByteArrayOutputStream parseErrorOutput = new ByteArrayOutputStream();
        new MCPServer().serve(new ByteArrayInputStream(parseErrorInput.getBytes(StandardCharsets.UTF_8)), parseErrorOutput);

        List<JsonObject> parseErrorResponses = parseJsonLines(parseErrorOutput.toString(StandardCharsets.UTF_8));
        assertEquals(1, parseErrorResponses.size());
        assertEquals(-32700, parseErrorResponses.get(0).getAsJsonObject("error").get("code").getAsInt());

        String invalidVersionInput = jsonLine("{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}")
                + jsonLine("{\"jsonrpc\":\"2.0\",\"method\":\"exit\",\"params\":{}}");
        ByteArrayOutputStream invalidVersionOutput = new ByteArrayOutputStream();
        new MCPServer().serve(new ByteArrayInputStream(invalidVersionInput.getBytes(StandardCharsets.UTF_8)), invalidVersionOutput);

        List<JsonObject> invalidVersionResponses = parseJsonLines(invalidVersionOutput.toString(StandardCharsets.UTF_8));
        assertEquals(1, invalidVersionResponses.size());
        assertEquals(1, invalidVersionResponses.get(0).get("id").getAsInt());
        assertEquals(-32600, invalidVersionResponses.get(0).getAsJsonObject("error").get("code").getAsInt());

        String invalidRequestInput = jsonLine("{\"jsonrpc\":\"2.0\",\"params\":{}}")
                + jsonLine("{\"jsonrpc\":\"2.0\",\"method\":\"exit\",\"params\":{}}");
        ByteArrayOutputStream invalidRequestOutput = new ByteArrayOutputStream();
        new MCPServer().serve(new ByteArrayInputStream(invalidRequestInput.getBytes(StandardCharsets.UTF_8)), invalidRequestOutput);

        List<JsonObject> invalidRequestResponses = parseJsonLines(invalidRequestOutput.toString(StandardCharsets.UTF_8));
        assertEquals(1, invalidRequestResponses.size());
        assertTrue(invalidRequestResponses.get(0).get("id").isJsonNull());
        assertEquals(-32600, invalidRequestResponses.get(0).getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void testToolStdoutDoesNotLeakIntoMcpProtocol(@TempDir Path tempDir) throws Exception {
        Path classesDir = TestFixtures.compileSources(tempDir, Map.of(
                "demo/App.java", "package demo; public class App { public String hi(){ return \"hi\"; } }"
        ));

        String input = jsonLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}")
                + jsonLine("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}")
                + jsonLine("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"decompile_class\",\"arguments\":{\"path\":\""
                + classesDir.resolve("demo/App.class").toString().replace("\\", "\\\\")
                + "\",\"engine\":\"vineflower\"}}}")
                + jsonLine("{\"jsonrpc\":\"2.0\",\"method\":\"exit\",\"params\":{}}");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new MCPServer().serve(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output);

        String outputText = output.toString(StandardCharsets.UTF_8);
        assertFalse(outputText.contains("INFO:"));
        List<JsonObject> responses = parseJsonLines(outputText);
        assertEquals(2, responses.size());
        assertTrue(responses.get(1).getAsJsonObject("result").getAsJsonArray("content").get(0)
                .getAsJsonObject().get("text").getAsString().contains("class App"));
    }

    @Test
    void testUtf8JsonLineParametersArePreserved(@TempDir Path tempDir) throws Exception {
        Path unicodeDir = tempDir.resolve("中文 目录");
        Path classesDir = TestFixtures.compileSources(unicodeDir, Map.of(
                "demo/App.java", "package demo; public class App { public String hi(){ return \"hi\"; } }"
        ));
        Path jarPath = TestFixtures.createJar(unicodeDir.resolve("应用.jar"), classesDir);

        String input = jsonLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"clientInfo\":{\"name\":\"测试客户端\",\"version\":\"1.0.0\"}}}")
                + jsonLine("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}")
                + jsonLine("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"list_classes\",\"arguments\":{\"path\":\""
                + jsonEscape(jarPath.toString())
                + "\"}}}")
                + jsonLine("{\"jsonrpc\":\"2.0\",\"method\":\"exit\",\"params\":{}}");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new MCPServer().serve(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output);

        List<JsonObject> responses = parseJsonLines(output.toString(StandardCharsets.UTF_8));
        assertEquals(2, responses.size());
        JsonObject toolResult = responses.get(1).getAsJsonObject("result");
        assertFalse(toolResult.get("isError").getAsBoolean());
        assertTrue(toolResult.getAsJsonArray("content").get(0).getAsJsonObject()
                .get("text").getAsString().contains("demo.App"));
    }

    private static String jsonLine(String json) {
        return json + "\n";
    }

    private static String jsonEscape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static List<JsonObject> parseJsonLines(String output) {
        return output.lines()
                .filter(line -> !line.isBlank())
                .map(line -> JsonParser.parseString(line).getAsJsonObject())
                .toList();
    }

}
