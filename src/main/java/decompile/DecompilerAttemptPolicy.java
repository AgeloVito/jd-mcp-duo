package decompile;

import java.util.List;
import java.util.Locale;

final class DecompilerAttemptPolicy {
    private DecompilerAttemptPolicy() {
    }

    static List<String> fallbackOrder(DecompilerOptions options) {
        return List.of(DecompilerEngines.JADX);
    }

    static boolean isUsableOutput(String source) {
        return source != null && !source.isBlank() && !containsFailureMarker(source);
    }

    static boolean containsFailureMarker(String source) {
        if (JdtMethodPatcher.containsFailureMarker(source)) {
            return true;
        }
        if (source == null || source.isBlank()) {
            return false;
        }
        String normalized = source.toLowerCase(Locale.ROOT);
        return normalized.contains("exception decompiling")
                || normalized.contains("couldn't be decompiled")
                || normalized.contains("could not be decompiled")
                || normalized.contains("failed to decompile")
                || normalized.contains("unable to decompile");
    }
}
