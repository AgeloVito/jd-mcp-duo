package decompile;

import jd.core.DecompilationResult;
import jd.core.links.ReferenceData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecompilationJsonTest {
    @Test
    void testMetadataLimitedSuppressesReferencesWhenMetadataWasNotRebuilt() {
        DecompilationResult result = new DecompilationResult();
        result.setDecompiledOutput("class App {}");
        result.setDeclarations(new LinkedHashMap<>());
        result.setTypeDeclarations(new TreeMap<>());
        result.setReferences(new ArrayList<>(List.of(new ReferenceData("demo/App", "demo/App", "run", "()V"))));
        result.setStrings(new ArrayList<>());
        result.setLineNumbers(new LinkedHashMap<>());
        result.setHyperlinks(new TreeMap<>());
        result.setMaxLineNumber(0);

        DecompilationOutcome outcome = new DecompilationOutcome(
                "demo/App",
                DecompilerEngines.JD_CORE_V1,
                DecompilerEngines.JD_CORE_V1,
                false,
                false,
                true,
                false,
                false,
                List.of(DecompilerEngines.JD_CORE_V1),
                Map.of(),
                result
        );

        assertEquals(0, DecompilationJson.toJson(outcome).getAsJsonArray("references").size());
    }

    @Test
    void testMethodPatchesAreExported() {
        DecompilationResult result = new DecompilationResult();
        result.setDecompiledOutput("class App { int bad() { return 1; } }");
        result.setDeclarations(new LinkedHashMap<>());
        result.setTypeDeclarations(new TreeMap<>());
        result.setReferences(new ArrayList<>());
        result.setStrings(new ArrayList<>());
        result.setLineNumbers(new LinkedHashMap<>());
        result.setHyperlinks(new TreeMap<>());
        result.setMaxLineNumber(1);

        DecompilationOutcome outcome = new DecompilationOutcome(
                "demo/App",
                DecompilerEngines.JD_CORE_DUO,
                DecompilerEngines.JD_CORE_V1,
                true,
                false,
                false,
                true,
                false,
                List.of(DecompilerEngines.JD_CORE_V1, DecompilerEngines.JD_CORE_V0),
                Map.of(),
                result,
                List.of(new JdtMethodPatcher.MethodPatch("Ldemo/App;.bad()I", 3, 5, 3, 3)),
                List.of("Ignored JD-Core v0-only preferences for JD-Core v1 attempt: OMIT_THIS_PREFIX")
        );

        var json = DecompilationJson.toJson(outcome);
        var patches = json.getAsJsonArray("methodPatches");

        assertEquals(1, patches.size());
        assertTrue(patches.get(0).getAsJsonObject().get("bindingKey").getAsString().contains("bad"));
        assertEquals(1, json.getAsJsonArray("warnings").size());
    }
}
