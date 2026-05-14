package support;

import java.io.PrintStream;

/**
 * Progress reporter for long-running tool operations.
 * Writes MCP progress notifications and/or stderr output.
 */
public final class ProgressReporter {

    private final String mcpProgressToken;
    private final String toolName;
    private final PrintStream mcpOut;
    private final long startNanos;
    private int lastReportedPercent;

    public ProgressReporter(String mcpProgressToken, String toolName) {
        this(mcpProgressToken, toolName, null);
    }

    public ProgressReporter(String mcpProgressToken, String toolName, PrintStream mcpOut) {
        this.mcpProgressToken = mcpProgressToken;
        this.toolName = toolName;
        this.mcpOut = mcpOut;
        this.startNanos = System.nanoTime();
        this.lastReportedPercent = -1;
    }

    /**
     * Report intermediate progress. Only sends notification when percentage changes
     * by at least 5% to avoid flooding the channel.
     */
    public void report(int current, int total) {
        if (total <= 0) return;
        int pct = (int) ((long) current * 100 / total);
        if (pct == lastReportedPercent) return;
        lastReportedPercent = pct;

        if (pct == 0) {
            System.err.printf("[jd-mcp-duo] %s: starting (%d total)%n", toolName, total);
        }

        if (mcpProgressToken != null && !mcpProgressToken.isBlank() && mcpOut != null) {
            mcpOut.printf(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{\"progressToken\":\"%s\",\"progress\":%d,\"total\":%d}}%n",
                mcpProgressToken, current, total
            );
        } else if (pct % 10 == 0) {
            System.err.printf("[jd-mcp-duo] %s: %d/%d (%d%%)%n", toolName, current, total, pct);
        }
    }

    /**
     * Send a final completion notification regardless of percentage dedup.
     */
    public void done() {
        if (mcpProgressToken != null && !mcpProgressToken.isBlank() && mcpOut != null) {
            mcpOut.printf(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{\"progressToken\":\"%s\",\"done\":true}}%n",
                mcpProgressToken
            );
        }
    }

    public long elapsedMillis() {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    public static String extractProgressToken(com.google.gson.JsonObject params) {
        if (params == null) return null;
        var meta = params.get("_meta");
        if (meta == null || !meta.isJsonObject()) return null;
        var token = meta.getAsJsonObject().get("progressToken");
        if (token == null || !token.isJsonPrimitive()) return null;
        return token.getAsString();
    }
}
