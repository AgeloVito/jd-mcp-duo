package decompile;

import jd.core.DecompilationResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

final class DecompilationCopies {
    private DecompilationCopies() {
    }

    static DecompilationOutcome copyOutcome(DecompilationOutcome outcome) {
        return new DecompilationOutcome(
                outcome.internalName(),
                outcome.engineRequested(),
                outcome.engineUsed(),
                outcome.patched(),
                outcome.fallbackUsed(),
                outcome.metadataLimited(),
                outcome.metadataRebuilt(),
                outcome.nativeAndroid(),
                java.util.List.copyOf(outcome.attemptedEngines()),
                Map.copyOf(outcome.engineFailures()),
                copyResult(outcome.result()),
                java.util.List.copyOf(outcome.methodPatches()),
                java.util.List.copyOf(outcome.warnings())
        );
    }

    static DecompilationResult copyResult(DecompilationResult original) {
        DecompilationResult copy = new DecompilationResult();
        copy.setDecompiledOutput(original.getDecompiledOutput());
        copy.setDeclarations(new LinkedHashMap<>(original.getDeclarations()));
        copy.setTypeDeclarations(new java.util.TreeMap<>(original.getTypeDeclarations()));
        copy.setReferences(new ArrayList<>(original.getReferences()));
        copy.setStrings(new ArrayList<>(original.getStrings()));
        copy.setLineNumbers(new LinkedHashMap<>(original.getLineNumbers()));
        copy.setHyperlinks(new java.util.TreeMap<>(original.getHyperlinks()));
        copy.setMaxLineNumber(original.getMaxLineNumber());
        return copy;
    }
}
