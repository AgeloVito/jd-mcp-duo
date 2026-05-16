package decompile;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecompilerOptionsTest {
    @Test
    void testJdCoreV0OnlyPreferencesAreIgnoredForV1AndWarned() {
        JsonObject arguments = new JsonObject();
        JsonObject preferences = new JsonObject();
        preferences.addProperty(jd.core.preferences.Preferences.OMIT_THIS_PREFIX, "true");
        preferences.addProperty(jd.core.preferences.Preferences.WRITE_METADATA, "true");
        arguments.add("preferences", preferences);

        DecompilerOptions options = DecompilerOptions.fromArguments(arguments, DecompilerEngines.AUTO);

        assertFalse(options.preferencesFor(DecompilerEngines.JD_CORE_V1).containsKey(jd.core.preferences.Preferences.OMIT_THIS_PREFIX));
        assertEquals("true", options.preferencesFor(DecompilerEngines.JD_CORE_V0).get(jd.core.preferences.Preferences.OMIT_THIS_PREFIX));
        assertTrue(options.preferenceWarningsForAttempts(List.of(DecompilerEngines.JD_CORE_V1))
                .get(0)
                .contains(jd.core.preferences.Preferences.OMIT_THIS_PREFIX));
    }
}
