package decompile;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

final class DecompilerAttemptPolicy {
    private DecompilerAttemptPolicy() {
    }

    static List<String> fallbackOrder(DecompilerOptions options, boolean jdCoreV0AlreadyAttempted) {
        LinkedHashSet<String> order = new LinkedHashSet<>();
        if (!jdCoreV0AlreadyAttempted) {
            order.add(DecompilerEngines.JD_CORE_V0);
        }
        switch (options.profile()) {
            case "accurate" -> {
                order.add(DecompilerEngines.VINEFLOWER);
                order.add(DecompilerEngines.CFR);
                order.add(DecompilerEngines.PROCYON);
                order.add(DecompilerEngines.FERNFLOWER);
                order.add(DecompilerEngines.JADX);
            }
            case "debuggable" -> {
                order.add(DecompilerEngines.PROCYON);
                order.add(DecompilerEngines.CFR);
                order.add(DecompilerEngines.VINEFLOWER);
                order.add(DecompilerEngines.FERNFLOWER);
                order.add(DecompilerEngines.JADX);
            }
            default -> {
                order.add(DecompilerEngines.VINEFLOWER);
                order.add(DecompilerEngines.CFR);
                order.add(DecompilerEngines.PROCYON);
                order.add(DecompilerEngines.JADX);
            }
        }
        return new ArrayList<>(order);
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
