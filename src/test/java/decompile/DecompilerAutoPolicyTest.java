package decompile;

import com.google.gson.JsonObject;
import jd.core.DecompilationResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DecompilerAutoPolicyTest {
    @Test
    void testAccurateProfileStillUsesAutoWhenEngineIsNotExplicit() {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("profile", "accurate");

        DecompilerOptions options = DecompilerOptions.fromArguments(arguments, DecompilerEngines.AUTO);

        assertEquals(DecompilerEngines.AUTO, options.requestedEngine());
        assertEquals("accurate", options.profile());
    }

    @Test
    void testFallbackPolicyDoesNotRepeatJdCoreV0AfterPatchAttempt() {
        DecompilerOptions options = new DecompilerOptions(
                DecompilerEngines.AUTO,
                "fast",
                null,
                false,
                false,
                List.of(),
                Map.of(),
                DecompilerOptions.DEFAULT_ATTEMPT_TIMEOUT_MILLIS
        );

        assertFalse(DecompilerAttemptPolicy.fallbackOrder(options, true).contains(DecompilerEngines.JD_CORE_V0));
        assertTrue(DecompilerAttemptPolicy.fallbackOrder(options, false).contains(DecompilerEngines.JD_CORE_V0));
    }

    @Test
    void testFallbackPolicyTriesJdCoreV0BeforeNonJdFallbacksWhenPatchWasNotAttempted() {
        DecompilerOptions options = new DecompilerOptions(
                DecompilerEngines.AUTO,
                "fast",
                null,
                false,
                false,
                List.of(),
                Map.of(),
                DecompilerOptions.DEFAULT_ATTEMPT_TIMEOUT_MILLIS
        );

        List<String> order = DecompilerAttemptPolicy.fallbackOrder(options, false);

        assertEquals(DecompilerEngines.JD_CORE_V0, order.get(0));
        assertTrue(order.indexOf(DecompilerEngines.JD_CORE_V0) < order.indexOf(DecompilerEngines.VINEFLOWER));
    }

    @Test
    void testFailureMarkersAreRejectedByQualityGate() {
        assertFalse(DecompilerAttemptPolicy.isUsableOutput("class A { // INTERNAL ERROR }"));
        assertFalse(DecompilerAttemptPolicy.isUsableOutput("/* Exception decompiling */ class A {}"));
        assertFalse(DecompilerAttemptPolicy.isUsableOutput("/* Couldn't be decompiled. */ class A {}"));
        assertTrue(DecompilerAttemptPolicy.isUsableOutput("class A { void ok(){} }"));
    }

    @Test
    void testNativeJadxAttemptHonorsTimeout() {
        DecompilerAttemptRunner.AttemptTimeoutException timeout = assertThrows(
                DecompilerAttemptRunner.AttemptTimeoutException.class,
                () -> NativeJadxSupport.runNativeAttempt(() -> {
                    Thread.sleep(5_000);
                    return new DecompilationResult();
                }, 10, "test-timeout")
        );

        assertTrue(timeout.getMessage().contains("timed out"));
    }

    @Test
    void testAttemptRunnerTimesOut() {
        DecompilerAttemptRunner.AttemptTimeoutException timeout = assertThrows(
                DecompilerAttemptRunner.AttemptTimeoutException.class,
                () -> DecompilerAttemptRunner.run(() -> {
                    Thread.sleep(5_000);
                    return new DecompilationResult();
                }, 10)
        );

        assertTrue(timeout.getMessage().contains("timed out"));
    }

    @Test
    void testThreadLocalStderrSilencerDoesNotSwallowOtherThreads() throws Exception {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(stderr)) {
            System.setErr(capture);
            CountDownLatch workerDone = new CountDownLatch(1);
            ThreadLocalStderrSilencer.callSilenced(() -> {
                System.err.print("hidden");
                Thread worker = new Thread(() -> {
                    System.err.print("visible");
                    workerDone.countDown();
                });
                worker.start();
                if (!workerDone.await(2, TimeUnit.SECONDS)) {
                    throw new IOException("worker did not write stderr");
                }
                return null;
            });
        } finally {
            System.setErr(originalErr);
        }

        String output = stderr.toString();
        assertFalse(output.contains("hidden"));
        assertTrue(output.contains("visible"));
    }
}
