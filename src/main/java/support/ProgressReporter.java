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
    private boolean tickLineActive;
    private int lastTickLineLength;

    public ProgressReporter(String mcpProgressToken, String toolName) {
        this(mcpProgressToken, toolName, null);
    }

    public ProgressReporter(String mcpProgressToken, String toolName, PrintStream mcpOut) {
        this.mcpProgressToken = mcpProgressToken;
        this.toolName = toolName;
        this.mcpOut = mcpOut;
        this.startNanos = System.nanoTime();
        this.lastReportedPercent = -1;
        this.tickLineActive = false;
    }

    /**
     * Report intermediate progress. Only sends notification when percentage changes.
     */
    public void report(int current, int total) {
        checkProgress(current, total);
    }

    /**
     * Called on each item during batch processing.
     * Prints percentage milestones on separate lines,
     * and a live-updating current-file line (overwrites itself with \r).
     */
    public void tick(int current, int total, String currentFile) {
        if (total <= 0) return;

        // Print progress first (percentage milestones)
        checkProgress(current, total);

        // Then update the live current-file line
        if (currentFile != null && !currentFile.isBlank()) {
            final int maxWidth = 140;
            String prefix = String.format("[jd-mcp-duo] %s -> ", toolName);
            String tickLine = prefix + currentFile;
            if (tickLine.length() > maxWidth) {
                int avail = maxWidth - prefix.length() - 3; // reserve for "…"
                int sep = currentFile.indexOf(" > ");
                if (sep > 0 && avail > 10) {
                    String archivePart = currentFile.substring(0, sep);
                    String classPart = currentFile.substring(sep + 3);
                    int lastSlash = archivePart.lastIndexOf('/');
                    String jarName = lastSlash >= 0 ? archivePart.substring(lastSlash + 1) : archivePart;
                    int firstSlash = archivePart.indexOf('/');
                    String firstDir = firstSlash > 0 ? archivePart.substring(0, firstSlash) : "";
                    int remaining = avail - jarName.length() - (firstDir.isEmpty() ? 0 : firstDir.length() + 2); // "/…"
                    if (remaining > 0) {
                        int classBudget = remaining * 2 / 3;
                        String classStr;
                        if (classPart.length() <= classBudget) {
                            classStr = classPart;
                        } else {
                            int headLen = classBudget / 3;
                            int tailLen = classBudget - headLen - 1; // 1 for "…"
                            classStr = classPart.substring(0, headLen) + "…"
                                    + classPart.substring(classPart.length() - tailLen);
                        }
                        String midPath = firstSlash > 0 && lastSlash > firstSlash + 1 ? "/…/" : "/";
                        tickLine = prefix + firstDir + midPath + jarName + " > " + classStr;
                    }
                } else {
                    int keep = Math.min(currentFile.length(), avail);
                    int head = keep * 7 / 10;
                    int tailStart = currentFile.length() - (keep - head);
                    tickLine = prefix + currentFile.substring(0, head) + "…"
                            + currentFile.substring(tailStart);
                }
            }
            // Safety net: never exceed maxWidth
            if (tickLine.length() > maxWidth) {
                tickLine = tickLine.substring(0, maxWidth - 1) + '…';
            }
            // Pad with spaces if shorter than previous tick to clear residual chars
            if (tickLineActive && tickLine.length() < lastTickLineLength) {
                tickLine = tickLine + " ".repeat(lastTickLineLength - tickLine.length());
            }
            lastTickLineLength = tickLine.length();
            if (tickLineActive) {
                System.err.print("\033[A\r" + tickLine + "\n");
            } else {
                System.err.print(tickLine + "\n");
                tickLineActive = true;
            }
            System.err.flush();
        }
    }

    private void checkProgress(int current, int total) {
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
        } else if (pct <= 10 || pct % 5 == 0) {
            clearTickLine();
            System.err.printf("[jd-mcp-duo] %s: %d/%d (%d%%)%n", toolName, current, total, pct);
        }
    }

    private void clearTickLine() {
        if (tickLineActive) {
            int width = Math.max(lastTickLineLength, 80);
            System.err.print("\033[A\r" + " ".repeat(width) + "\r");
            System.err.flush();
            tickLineActive = false;
        }
    }

    /**
     * Send a final completion notification regardless of percentage dedup.
     */
    public void done() {
        tickLineActive = false;
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
