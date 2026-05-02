package decompile;

import archive.ClassLocation;
import archive.InputContainer;
import com.heliosdecompiler.transformerapi.common.Loader;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public final class DecompilerSession implements AutoCloseable {
    private final InputContainer container;
    private final DecompilerOptions options;
    private final ClassSourceResolver resolver;
    private final Loader loader;

    private DecompilerSession(InputContainer container,
                              DecompilerOptions options,
                              ClassSourceResolver resolver,
                              Loader loader) {
        this.container = container;
        this.options = options;
        this.resolver = resolver;
        this.loader = loader;
    }

    public static DecompilerSession open(InputContainer container, DecompilerOptions options) throws IOException {
        ClassSourceResolver resolver = ClassSourceResolver.open(container, options.advancedLookup(), options.classpathEntries(), options.releaseVersion());
        return new DecompilerSession(container, options, resolver, resolver.createLoader());
    }

    public DecompilationOutcome decompile(String classNameOrInternal) throws Exception {
        ClassLocation classLocation = container.resolveClass(classNameOrInternal);
        if (classLocation == null) {
            throw new IOException("Class not found: " + classNameOrInternal);
        }
        DecompilationOutcome cached = DecompilationCache.get(container, classLocation.internalName(), options);
        if (cached != null) {
            return cached;
        }
        DecompilationOutcome outcome;
        if (NativeJadxSupport.supports(container.kind())
                && (DecompilerEngines.AUTO.equals(options.requestedEngine()) || DecompilerEngines.JADX.equals(options.requestedEngine()))) {
            ArrayList<String> attemptedEngines = new ArrayList<>();
            LinkedHashMap<String, String> engineFailures = new LinkedHashMap<>();
            DecompilationOutcome nativeOutcome = NativeJadxSupport.tryDecompile(
                    container,
                    classLocation.internalName(),
                    options,
                    attemptedEngines,
                    engineFailures,
                    false
            );
            if (nativeOutcome != null) {
                DecompilationCache.put(container, classLocation.internalName(), options, nativeOutcome);
                return nativeOutcome;
            }
            if (DecompilerEngines.AUTO.equals(options.requestedEngine())) {
                outcome = DecompilerSupport.decompileAuto(loader, resolver, classLocation, container.contextUri(), options, attemptedEngines, engineFailures);
                DecompilationCache.put(container, classLocation.internalName(), options, outcome);
                return outcome;
            }
        }
        if (DecompilerEngines.AUTO.equals(options.requestedEngine())) {
            outcome = DecompilerSupport.decompileAuto(loader, resolver, classLocation, container.contextUri(), options);
            DecompilationCache.put(container, classLocation.internalName(), options, outcome);
            return outcome;
        }
        outcome = DecompilerSupport.decompileWithEngine(
                loader,
                resolver,
                classLocation,
                options,
                options.requestedEngine(),
                false
        );
        DecompilationCache.put(container, classLocation.internalName(), options, outcome);
        return outcome;
    }

    public List<String> parserClasspathEntries() {
        return resolver.parserClasspathEntries();
    }

    public URI contextUri() {
        return container.contextUri();
    }

    @Override
    public void close() throws IOException {
        resolver.close();
    }
}
