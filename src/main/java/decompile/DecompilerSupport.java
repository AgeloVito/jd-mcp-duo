package decompile;

import archive.ClassLocation;
import archive.InputContainer;
import com.heliosdecompiler.transformerapi.StandardTransformers;
import com.heliosdecompiler.transformerapi.common.Loader;
import jd.core.DecompilationResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DecompilerSupport {
    private DecompilerSupport() {
    }

    public static DecompilationOutcome decompile(InputContainer container,
                                                 String classNameOrInternal,
                                                 DecompilerOptions options) throws Exception {
        try (DecompilerSession session = DecompilerSession.open(container, options)) {
            return session.decompile(classNameOrInternal);
        }
    }

    static DecompilationOutcome decompileAuto(Loader loader,
                                              ClassSourceResolver resolver,
                                              ClassLocation classLocation,
                                              java.net.URI contextUri,
                                              DecompilerOptions options) throws Exception {
        return decompileAuto(loader, resolver, classLocation, contextUri, options, new ArrayList<>(), new LinkedHashMap<>());
    }

    static DecompilationOutcome decompileAuto(Loader loader,
                                              ClassSourceResolver resolver,
                                              ClassLocation classLocation,
                                              java.net.URI contextUri,
                                              DecompilerOptions options,
                                              List<String> attemptedEngines,
                                              Map<String, String> engineFailures) throws Exception {
        List<String> attempted = new ArrayList<>(attemptedEngines);
        LinkedHashMap<String, String> failures = new LinkedHashMap<>(engineFailures);

        DecompilationResult v1Result = attemptEngine(loader, classLocation, options, DecompilerEngines.JD_CORE_V1, attempted, failures, true);
        if (v1Result != null && DecompilerAttemptPolicy.isUsableOutput(v1Result.getDecompiledOutput())) {
            return outcome(options, classLocation.internalName(), options.requestedEngine(), DecompilerEngines.JD_CORE_V1, false, false, false, false, false, attempted, failures, v1Result);
        }

        DecompilationResult v0Result = null;
        if (v1Result != null) {
            v0Result = attemptEngine(loader, classLocation, options, DecompilerEngines.JD_CORE_V0, attempted, failures, true);
            if (v0Result != null) {
                DecompilationOutcome patchedOutcome = patchV1Result(
                        v1Result,
                        v0Result,
                        classLocation.internalName(),
                        options.requestedEngine(),
                        contextUri,
                        resolver.parserClasspathEntries(),
                        attempted,
                        failures,
                        options.preferenceWarningsForAttempts(attempted)
                );
                if (patchedOutcome != null) {
                    return patchedOutcome;
                }
                if (DecompilerAttemptPolicy.isUsableOutput(v0Result.getDecompiledOutput())) {
                    return outcome(options, classLocation.internalName(), options.requestedEngine(), DecompilerEngines.JD_CORE_V0, false, true, false, false, false, attempted, failures, v0Result);
                }
            }
        }

        for (String engine : DecompilerAttemptPolicy.fallbackOrder(options)) {
            DecompilationResult result = attemptEngine(loader, classLocation, options, engine, attempted, failures, false);
            if (result != null) {
                return outcome(options, classLocation.internalName(), options.requestedEngine(), engine, false, true, false, false, false, attempted, failures, result);
            }
        }

        throw new IOException("All decompiler engines failed for " + classLocation.displayName() + ": " + failures);
    }

    static DecompilationOutcome decompileWithEngine(Loader loader,
                                                    ClassSourceResolver resolver,
                                                    ClassLocation classLocation,
                                                    DecompilerOptions options,
                                                    String engine,
                                                    boolean patched) throws Exception {
        if (DecompilerEngines.JD_CORE_DUO.equals(engine)) {
            return decompileJdCoreDuo(loader, resolver, classLocation, options);
        }
        if (DecompilerEngines.JD_CORE_V1.equals(engine)) {
            return decompileJdCoreV1WithPatch(loader, resolver, classLocation, options);
        }
        DecompilationResult result = runDecompiler(
                loader,
                classLocation.internalName(),
                options.preferencesFor(engine),
                engine,
                options.attemptTimeoutMillis()
        );
        if (result.getDecompiledOutput() == null || result.getDecompiledOutput().isBlank()) {
            throw new IOException("Decompilation result is empty for engine " + engine);
        }
        if (!DecompilerAttemptPolicy.isUsableOutput(result.getDecompiledOutput())) {
            throw new IOException("Decompilation result contains a failure marker for engine " + engine);
        }
        return outcome(options, classLocation.internalName(), options.requestedEngine(), engine, patched, false, false, false, false, List.of(engine), Map.of(), result);
    }

    static DecompilationOutcome decompileJdCoreDuo(Loader loader,
                                                   ClassSourceResolver resolver,
                                                   ClassLocation classLocation,
                                                   DecompilerOptions options) throws Exception {
        List<String> attempted = new ArrayList<>();
        LinkedHashMap<String, String> failures = new LinkedHashMap<>();

        DecompilationResult v1Result = attemptEngine(
                loader,
                classLocation,
                options,
                DecompilerEngines.JD_CORE_V1,
                attempted,
                failures,
                true
        );
        if (v1Result != null && DecompilerAttemptPolicy.isUsableOutput(v1Result.getDecompiledOutput())) {
            return outcome(options, classLocation.internalName(), options.requestedEngine(), DecompilerEngines.JD_CORE_V1, false, false, false, false, false, attempted, failures, v1Result);
        }

        DecompilationResult v0Result = attemptEngine(
                loader,
                classLocation,
                options,
                DecompilerEngines.JD_CORE_V0,
                attempted,
                failures,
                true
        );
        if (v1Result != null && v0Result != null) {
            DecompilationOutcome patchedOutcome = patchV1Result(
                    v1Result,
                    v0Result,
                    classLocation.internalName(),
                    options.requestedEngine(),
                    resolver == null ? null : resolver.primaryContextUri(),
                    resolver == null ? List.of() : resolver.parserClasspathEntries(),
                    attempted,
                    failures,
                    options.preferenceWarningsForAttempts(attempted)
            );
            if (patchedOutcome != null) {
                return patchedOutcome;
            }
        }
        if (v0Result != null && DecompilerAttemptPolicy.isUsableOutput(v0Result.getDecompiledOutput())) {
            return outcome(options, classLocation.internalName(), options.requestedEngine(), DecompilerEngines.JD_CORE_V0, false, true, false, false, false, attempted, failures, v0Result);
        }

        throw new IOException("JD-Core duo failed for " + classLocation.displayName() + ": " + failures);
    }

    private static DecompilationOutcome decompileJdCoreV1WithPatch(Loader loader,
                                                                   ClassSourceResolver resolver,
                                                                   ClassLocation classLocation,
                                                                   DecompilerOptions options) throws Exception {
        List<String> attempted = new ArrayList<>();
        LinkedHashMap<String, String> failures = new LinkedHashMap<>();

        DecompilationResult v1Result = attemptEngine(
                loader,
                classLocation,
                options,
                DecompilerEngines.JD_CORE_V1,
                attempted,
                failures,
                true
        );
        if (v1Result == null) {
            throw new IOException("JD-Core v1 failed for " + classLocation.displayName() + ": " + failures);
        }
        if (DecompilerAttemptPolicy.isUsableOutput(v1Result.getDecompiledOutput())) {
            return outcome(options, classLocation.internalName(), options.requestedEngine(), DecompilerEngines.JD_CORE_V1, false, false, false, false, false, attempted, failures, v1Result);
        }

        DecompilationResult v0Result = attemptEngine(
                loader,
                classLocation,
                options,
                DecompilerEngines.JD_CORE_V0,
                attempted,
                failures,
                true
        );
        if (v0Result != null) {
            DecompilationOutcome patchedOutcome = patchV1Result(
                    v1Result,
                    v0Result,
                    classLocation.internalName(),
                    options.requestedEngine(),
                    resolver == null ? null : resolver.primaryContextUri(),
                    resolver == null ? List.of() : resolver.parserClasspathEntries(),
                    attempted,
                    failures,
                    options.preferenceWarningsForAttempts(attempted)
            );
            if (patchedOutcome != null) {
                return patchedOutcome;
            }
        }

        throw new IOException("JD-Core v1 output contains failure markers and JD-Core v0 method patching failed for "
                + classLocation.displayName() + ": " + failures);
    }

    static DecompilationOutcome patchV1Result(DecompilationResult v1Result,
                                              DecompilationResult v0Result,
                                              String internalName,
                                              String engineRequested,
                                              java.net.URI contextUri,
                                              List<String> parserClasspathEntries,
                                              List<String> attemptedEngines,
                                              Map<String, String> engineFailures) {
        return patchV1Result(
                v1Result,
                v0Result,
                internalName,
                engineRequested,
                contextUri,
                parserClasspathEntries,
                attemptedEngines,
                engineFailures,
                List.of()
        );
    }

    static DecompilationOutcome patchV1Result(DecompilationResult v1Result,
                                              DecompilationResult v0Result,
                                              String internalName,
                                              String engineRequested,
                                              java.net.URI contextUri,
                                              List<String> parserClasspathEntries,
                                              List<String> attemptedEngines,
                                              Map<String, String> engineFailures,
                                              List<String> warnings) {
        try {
            String unitName = internalName + ".class";
            JdtMethodPatcher.PatchResult patchResult = JdtMethodPatcher.patchFailedMethodsDetailed(
                    v1Result.getDecompiledOutput(),
                    v0Result.getDecompiledOutput(),
                    unitName,
                    contextUri,
                    parserClasspathEntries
            );
            if (patchResult.methodPatches().isEmpty() || patchResult.source().equals(v1Result.getDecompiledOutput())) {
                return null;
            }

            DecompilationResult patchedResult = DecompilationCopies.copyResult(v1Result);
            patchedResult.setDecompiledOutput(patchResult.source());
            boolean metadataRebuilt = PatchedMetadataSupport.rebuildPatchedMetadata(
                    patchedResult,
                    v1Result,
                    v0Result,
                    patchResult.source(),
                    patchResult,
                    internalName + ".java",
                    contextUri,
                    parserClasspathEntries
            );
            return outcome(
                    internalName,
                    engineRequested,
                    DecompilerEngines.JD_CORE_V1,
                    true,
                    false,
                    !metadataRebuilt,
                    metadataRebuilt,
                    false,
                    attemptedEngines,
                    engineFailures,
                    patchedResult,
                    patchResult.methodPatches(),
                    warnings
            );
        } catch (Exception e) {
            engineFailures.put("patch-jd-core", summarizeFailure(e));
            return null;
        }
    }

    private static DecompilationOutcome outcome(DecompilerOptions options,
                                                String internalName,
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
        return outcome(
                internalName,
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
                options.preferenceWarningsForAttempts(attemptedEngines)
        );
    }

    private static DecompilationOutcome outcome(String internalName,
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
        return outcome(
                internalName,
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
                List.of()
        );
    }

    private static DecompilationOutcome outcome(String internalName,
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
        return new DecompilationOutcome(
                internalName,
                engineRequested,
                engineUsed,
                patched,
                fallbackUsed,
                metadataLimited,
                metadataRebuilt,
                nativeAndroid,
                List.copyOf(attemptedEngines),
                Map.copyOf(engineFailures),
                result,
                methodPatches,
                warnings
        );
    }

    private static DecompilationResult attemptEngine(Loader loader,
                                                     ClassLocation classLocation,
                                                     DecompilerOptions options,
                                                     String engine,
                                                     List<String> attemptedEngines,
                                                     Map<String, String> engineFailures,
                                                     boolean allowFailureMarker) {
        attemptedEngines.add(engine);
        try {
            DecompilationResult result = runDecompiler(
                    loader,
                    classLocation.internalName(),
                    options.preferencesFor(engine),
                    engine,
                    options.attemptTimeoutMillis()
            );
            if (result.getDecompiledOutput() == null || result.getDecompiledOutput().isBlank()) {
                throw new IOException("Decompilation result is empty");
            }
            if (!allowFailureMarker && !DecompilerAttemptPolicy.isUsableOutput(result.getDecompiledOutput())) {
                throw new IOException("Decompilation result contains a failure marker");
            }
            return result;
        } catch (Throwable e) {
            engineFailures.put(engine, summarizeFailure(e));
            return null;
        }
    }

    private static DecompilationResult runDecompiler(Loader loader,
                                                     String internalName,
                                                     Map<String, String> preferences,
                                                     String engine,
                                                     long timeoutMillis) throws Exception {
        return DecompilerAttemptRunner.run(
                () -> ThreadLocalStderrSilencer.callSilenced(
                        () -> StandardTransformers.decompile(loader, internalName, preferences, engine)
                ),
                timeoutMillis,
                internalName + " (" + engine + ")"
        );
    }

    private static String summarizeFailure(Throwable e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
