package decompile;

import com.heliosdecompiler.transformerapi.StandardTransformers;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DecompilerEngines {
    public static final String AUTO = "auto";
    public static final String JD_CORE_V0 = StandardTransformers.Decompilers.ENGINE_JD_CORE_V0;
    public static final String JD_CORE_V1 = StandardTransformers.Decompilers.ENGINE_JD_CORE_V1;
    public static final String CFR = StandardTransformers.Decompilers.ENGINE_CFR;
    public static final String PROCYON = StandardTransformers.Decompilers.ENGINE_PROCYON;
    public static final String FERNFLOWER = StandardTransformers.Decompilers.ENGINE_FERNFLOWER;
    public static final String VINEFLOWER = StandardTransformers.Decompilers.ENGINE_VINEFLOWER;
    public static final String JADX = StandardTransformers.Decompilers.ENGINE_JADX;

    private static final Map<String, String> ALIASES = new LinkedHashMap<>();

    static {
        ALIASES.put("auto", AUTO);
        ALIASES.put("jd-core", JD_CORE_V1);
        ALIASES.put("jd-core-v1", JD_CORE_V1);
        ALIASES.put("jdcore", JD_CORE_V1);
        ALIASES.put("jd", JD_CORE_V1);
        ALIASES.put("jd-core-v0", JD_CORE_V0);
        ALIASES.put("jdcore-v0", JD_CORE_V0);
        ALIASES.put("v0", JD_CORE_V0);
        ALIASES.put("cfr", CFR);
        ALIASES.put("procyon", PROCYON);
        ALIASES.put("fernflower", FERNFLOWER);
        ALIASES.put("ff", FERNFLOWER);
        ALIASES.put("vineflower", VINEFLOWER);
        ALIASES.put("vf", VINEFLOWER);
        ALIASES.put("jadx", JADX);
    }

    private DecompilerEngines() {
    }

    public static String normalize(String requestedEngine) {
        if (requestedEngine == null || requestedEngine.isBlank()) {
            return AUTO;
        }
        String normalized = ALIASES.get(requestedEngine.toLowerCase());
        if (normalized == null) {
            throw new IllegalArgumentException("Unsupported decompiler engine: " + requestedEngine);
        }
        return normalized;
    }

    public static String normalizeOrNull(String requestedEngine) {
        if (requestedEngine == null || requestedEngine.isBlank()) {
            return AUTO;
        }
        return ALIASES.get(requestedEngine.toLowerCase());
    }

    public static Map<String, String> aliases() {
        return Collections.unmodifiableMap(ALIASES);
    }
}
