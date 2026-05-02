package decompile;

import java.util.ArrayList;
import java.util.List;

public final class DecompilationSummary {
    private DecompilationSummary() {
    }

    public static String inlineStatus(DecompilationOutcome outcome) {
        List<String> parts = new ArrayList<>();
        if (outcome.patched()) {
            parts.add("patchedMethods=" + outcome.methodPatches().size());
            parts.add("metadata=" + metadataState(outcome));
        }
        if (outcome.fallbackUsed()) {
            parts.add("fallback");
        }
        if (!outcome.warnings().isEmpty()) {
            parts.add("warnings=" + outcome.warnings().size());
        }
        return String.join(", ", parts);
    }

    public static String detailBlock(DecompilationOutcome outcome) {
        List<String> lines = new ArrayList<>();
        if (outcome.patched()) {
            lines.add("Patched methods: " + outcome.methodPatches().size());
            lines.add("Patch metadata: " + metadataState(outcome));
        }
        if (!outcome.warnings().isEmpty()) {
            lines.add("Warnings:");
            outcome.warnings().forEach(warning -> lines.add("- " + warning));
        }
        return String.join(System.lineSeparator(), lines);
    }

    private static String metadataState(DecompilationOutcome outcome) {
        if (outcome.metadataRebuilt()) {
            return "rebuilt";
        }
        if (outcome.metadataLimited()) {
            return "limited";
        }
        return "original";
    }
}
