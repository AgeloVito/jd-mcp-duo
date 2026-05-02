package decompile;

import jd.core.DecompilationResult;

import java.util.List;
import java.util.Map;

public record DecompilationOutcome(String internalName,
                                   String engineRequested,
                                   String engineUsed,
                                   boolean patched,
                                   boolean fallbackUsed,
                                   boolean metadataLimited,
                                   boolean metadataRebuilt,
                                   boolean nativeAndroid,
                                   List<String> attemptedEngines,
                                   Map<String, String> engineFailures,
                                   DecompilationResult result,
                                   List<JdtMethodPatcher.MethodPatch> methodPatches,
                                   List<String> warnings) {
    public DecompilationOutcome(String internalName,
                                String engineRequested,
                                String engineUsed,
                                boolean patched,
                                boolean fallbackUsed,
                                boolean metadataLimited,
                                boolean metadataRebuilt,
                                boolean nativeAndroid,
                                List<String> attemptedEngines,
                                Map<String, String> engineFailures,
                                DecompilationResult result) {
        this(internalName,
                engineRequested,
                engineUsed,
                patched,
                fallbackUsed,
                metadataLimited,
                metadataRebuilt,
                nativeAndroid,
                attemptedEngines,
                engineFailures,
                result,
                List.of(),
                List.of());
    }

    public DecompilationOutcome(String internalName,
                                String engineRequested,
                                String engineUsed,
                                boolean patched,
                                boolean fallbackUsed,
                                boolean metadataLimited,
                                boolean metadataRebuilt,
                                boolean nativeAndroid,
                                List<String> attemptedEngines,
                                Map<String, String> engineFailures,
                                DecompilationResult result,
                                List<JdtMethodPatcher.MethodPatch> methodPatches) {
        this(internalName,
                engineRequested,
                engineUsed,
                patched,
                fallbackUsed,
                metadataLimited,
                metadataRebuilt,
                nativeAndroid,
                attemptedEngines,
                engineFailures,
                result,
                methodPatches,
                List.of());
    }

    public DecompilationOutcome {
        attemptedEngines = attemptedEngines == null ? List.of() : List.copyOf(attemptedEngines);
        engineFailures = engineFailures == null ? Map.of() : Map.copyOf(engineFailures);
        methodPatches = methodPatches == null ? List.of() : List.copyOf(methodPatches);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
