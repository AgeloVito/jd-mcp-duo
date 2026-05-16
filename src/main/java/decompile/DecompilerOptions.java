package decompile;

import support.JsonUtils;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.heliosdecompiler.transformerapi.decompilers.cfr.CFRSettings;
import com.heliosdecompiler.transformerapi.decompilers.fernflower.FernflowerSettings;
import com.heliosdecompiler.transformerapi.decompilers.jadx.MapJadxArgs;
import com.heliosdecompiler.transformerapi.decompilers.jd.JDSettings;
import com.heliosdecompiler.transformerapi.decompilers.procyon.MapDecompilerSettings;
import com.heliosdecompiler.transformerapi.decompilers.vineflower.VineflowerSettings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record DecompilerOptions(
        String requestedEngine,
        String profile,
        Integer releaseVersion,
        boolean lineNumbers,
        boolean advancedLookup,
        List<String> classpathEntries,
        Map<String, String> userPreferences,
        long attemptTimeoutMillis
) {
    public static final long DEFAULT_ATTEMPT_TIMEOUT_MILLIS = 30_000L;
    private static final Set<String> JD_CORE_V0_ONLY_PREFERENCES = Set.of(
            jd.core.preferences.Preferences.OMIT_THIS_PREFIX,
            jd.core.preferences.Preferences.DISPLAY_DEFAULT_CONSTRUCTOR
    );

    public static DecompilerOptions fromArguments(JsonObject arguments, String defaultEngine) {
        String profile = JsonUtils.getString(arguments, "profile", "fast").toLowerCase();
        if (!List.of("fast", "accurate", "debuggable").contains(profile)) {
            throw new IllegalArgumentException("Unsupported decompilation profile: " + profile + ". Expected fast, accurate, or debuggable.");
        }
        String requestedEngine = JsonUtils.getString(arguments, "engine", null);
        String engine = DecompilerEngines.normalize(resolveDefaultEngine(requestedEngine, profile, defaultEngine));
        Integer releaseVersion = arguments.has("releaseVersion") && !arguments.get("releaseVersion").isJsonNull()
                ? JsonUtils.getInt(arguments, "releaseVersion", Runtime.version().feature())
                : null;
        boolean lineNumbers = JsonUtils.getBoolean(arguments, "lineNumbers", "debuggable".equals(profile));
        boolean advancedLookup = JsonUtils.getBoolean(arguments, "advancedLookup", false);
        long attemptTimeoutMillis = JsonUtils.getInt(arguments, "attemptTimeoutMillis", (int) DEFAULT_ATTEMPT_TIMEOUT_MILLIS);
        if (attemptTimeoutMillis < 0) {
            throw new IllegalArgumentException("attemptTimeoutMillis must be >= 0");
        }
        List<String> classpathEntries = new ArrayList<>(JsonUtils.getStringList(arguments, "classpath"));
        Map<String, String> preferences = new LinkedHashMap<>();

        JsonObject preferencesObject = JsonUtils.getObject(arguments, "preferences");
        for (Map.Entry<String, JsonElement> entry : preferencesObject.entrySet()) {
            preferences.put(entry.getKey(), entry.getValue().getAsString());
        }

        return new DecompilerOptions(
                engine,
                profile,
                releaseVersion,
                lineNumbers,
                advancedLookup,
                classpathEntries.isEmpty() ? List.of() : List.copyOf(classpathEntries),
                Map.copyOf(preferences),
                attemptTimeoutMillis
        );
    }

    public Map<String, String> preferencesFor(String normalizedEngine) {
        LinkedHashMap<String, String> merged = new LinkedHashMap<>();
        merged.putAll(defaultsFor(normalizedEngine, lineNumbers));
        for (Map.Entry<String, String> entry : userPreferences.entrySet()) {
            if (DecompilerEngines.JD_CORE_V1.equals(normalizedEngine) && JD_CORE_V0_ONLY_PREFERENCES.contains(entry.getKey())) {
                continue;
            }
            merged.put(entry.getKey(), entry.getValue());
        }
        return merged;
    }

    public List<String> preferenceWarningsForAttempts(List<String> attemptedEngines) {
        if (!attemptedEngines.contains(DecompilerEngines.JD_CORE_V1)) {
            return List.of();
        }
        LinkedHashSet<String> ignored = new LinkedHashSet<>();
        for (String key : userPreferences.keySet()) {
            if (JD_CORE_V0_ONLY_PREFERENCES.contains(key)) {
                ignored.add(key);
            }
        }
        if (ignored.isEmpty()) {
            return List.of();
        }
        return List.of("Ignored JD-Core v0-only preferences for JD-Core v1 attempt: " + String.join(", ", ignored));
    }

    private static String resolveDefaultEngine(String requestedEngine, String profile, String defaultEngine) {
        if (requestedEngine != null && !requestedEngine.isBlank()) {
            return requestedEngine;
        }
        return defaultEngine;
    }

    private static Map<String, String> defaultsFor(String engine, boolean lineNumbers) {
        return switch (engine) {
            case DecompilerEngines.JD_CORE_V0, DecompilerEngines.JD_CORE_V1 -> lineNumbers ? JDSettings.lineNumbers() : JDSettings.defaults();
            case DecompilerEngines.CFR -> lineNumbers ? CFRSettings.lineNumbers() : CFRSettings.defaults();
            case DecompilerEngines.FERNFLOWER -> lineNumbers ? FernflowerSettings.lineNumbers() : FernflowerSettings.defaults();
            case DecompilerEngines.VINEFLOWER -> lineNumbers ? VineflowerSettings.lineNumbers() : VineflowerSettings.defaults();
            case DecompilerEngines.PROCYON -> lineNumbers ? MapDecompilerSettings.lineNumbers() : MapDecompilerSettings.defaults();
            case DecompilerEngines.JADX -> lineNumbers ? MapJadxArgs.lineNumbers() : MapJadxArgs.defaults();
            default -> lineNumbers ? JDSettings.lineNumbers() : JDSettings.defaults();
        };
    }
}
