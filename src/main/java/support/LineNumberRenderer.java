package support;

import decompile.DecompilationOutcome;

import java.util.Map;

public final class LineNumberRenderer {
    private LineNumberRenderer() {
    }

    public static String render(DecompilationOutcome outcome, String mode) {
        String normalized = normalize(mode);
        if (outcome == null || normalized == null) {
            return outcome == null || outcome.result() == null ? "" : outcome.result().getDecompiledOutput();
        }
        String source = outcome.result().getDecompiledOutput();
        String[] lines = source.split("\\R", -1);
        StringBuilder rendered = new StringBuilder(source.length() + lines.length * 12);
        Map<Integer, Integer> lineNumbers = outcome.result().getLineNumbers();
        for (int index = 0; index < lines.length; index++) {
            int decompiledLine = index + 1;
            Integer sourceLine = lineNumbers.get(decompiledLine);
            rendered.append(prefix(normalized, decompiledLine, sourceLine)).append(lines[index]);
            if (index + 1 < lines.length) {
                rendered.append(System.lineSeparator());
            }
        }
        return rendered.toString();
    }

    public static String normalize(String mode) {
        if (mode == null || mode.isBlank() || "none".equalsIgnoreCase(mode)) {
            return null;
        }
        String normalized = mode.toLowerCase();
        return switch (normalized) {
            case "decompiled", "source", "both" -> normalized;
            default -> throw new IllegalArgumentException("Unsupported renderLineNumbers mode: " + mode);
        };
    }

    private static String prefix(String mode, int decompiledLine, Integer sourceLine) {
        return switch (mode) {
            case "decompiled" -> String.format("%4d | ", decompiledLine);
            case "source" -> String.format("%4s | ", sourceLine == null ? "" : sourceLine);
            case "both" -> String.format("%4d | %4s | ", decompiledLine, sourceLine == null ? "" : sourceLine);
            default -> "";
        };
    }
}
