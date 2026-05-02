package decompile;

import archive.InputContainer;
import archive.InputContainers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import testing.TestFixtures;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassSourceResolverTest {
    @Test
    void testNestedBootLibIsAvailableToResolver(@TempDir Path tempDir) throws Exception {
        Path helperClasses = TestFixtures.compileSources(tempDir.resolve("helper-src"), Map.of(
                "dep/Helper.java", "package dep; public class Helper { public static String answer(){ return \"nested\"; } }"
        ));
        Path mainClasses = TestFixtures.compileSources(tempDir.resolve("main-src"), Map.of(
                "app/Main.java", "package app; public class Main { public String run(){ return dep.Helper.answer(); } }"
        ), java.util.List.of(helperClasses));
        Path helperJar = TestFixtures.createJar(tempDir.resolve("helper-1.0.0.jar"), helperClasses);
        Path appJar = TestFixtures.createJar(
                tempDir.resolve("app.jar"),
                mainClasses,
                "BOOT-INF/classes/",
                Map.of("BOOT-INF/lib/helper-1.0.0.jar", Files.readAllBytes(helperJar))
        );

        try (InputContainer container = InputContainers.open(appJar);
             ClassSourceResolver resolver = ClassSourceResolver.open(container, false, java.util.List.of())) {
            byte[] helperBytes = resolver.load("dep/Helper");
            assertNotNull(helperBytes);
            assertTrue(new String(helperBytes, StandardCharsets.ISO_8859_1).contains("nested"));
        }
    }

    @Test
    void testHigherVersionNestedDependencyWinsConflict(@TempDir Path tempDir) throws Exception {
        Path helperV1Classes = TestFixtures.compileSources(tempDir.resolve("helper-v1-src"), Map.of(
                "dep/Helper.java", "package dep; public class Helper { public static String answer(){ return \"v1\"; } }"
        ));
        Path helperV2Classes = TestFixtures.compileSources(tempDir.resolve("helper-v2-src"), Map.of(
                "dep/Helper.java", "package dep; public class Helper { public static String answer(){ return \"v2\"; } }"
        ));
        Path mainClasses = TestFixtures.compileSources(tempDir.resolve("main-src"), Map.of(
                "app/Main.java", "package app; public class Main { public String run(){ return dep.Helper.answer(); } }"
        ), java.util.List.of(helperV2Classes));
        Path helperV1Jar = TestFixtures.createJar(tempDir.resolve("helper-1.0.0.jar"), helperV1Classes);
        Path helperV2Jar = TestFixtures.createJar(tempDir.resolve("helper-2.0.0.jar"), helperV2Classes);
        Path appJar = TestFixtures.createJar(
                tempDir.resolve("app.jar"),
                mainClasses,
                "BOOT-INF/classes/",
                Map.of(
                        "BOOT-INF/lib/helper-1.0.0.jar", Files.readAllBytes(helperV1Jar),
                        "BOOT-INF/lib/helper-2.0.0.jar", Files.readAllBytes(helperV2Jar)
                )
        );

        try (InputContainer container = InputContainers.open(appJar);
             ClassSourceResolver resolver = ClassSourceResolver.open(container, false, java.util.List.of())) {
            byte[] helperBytes = resolver.load("dep/Helper");
            assertNotNull(helperBytes);
            String classBytes = new String(helperBytes, StandardCharsets.ISO_8859_1);
            assertTrue(classBytes.contains("v2"));
            assertTrue(!classBytes.contains("v1"));
        }
    }

    @Test
    void testExplicitClasspathHonorsReleaseVersion(@TempDir Path tempDir) throws Exception {
        Path baseClasses = TestFixtures.compileSources(tempDir.resolve("dep-base"), Map.of(
                "dep/Helper.java", "package dep; public class Helper { public static String value(){ return \"base\"; } }"
        ));
        Path versionedClasses = TestFixtures.compileSources(tempDir.resolve("dep-mr"), Map.of(
                "dep/Helper.java", "package dep; public class Helper { public static String value(){ return \"mr17\"; } }"
        ));
        Path depJar = TestFixtures.createMultiReleaseJar(tempDir.resolve("dep.jar"), baseClasses, versionedClasses, "dep/Helper", 17);
        Path appClasses = TestFixtures.compileSources(tempDir.resolve("app"), Map.of(
                "app/Main.java", "package app; public class Main { }"
        ));
        Path appJar = TestFixtures.createJar(tempDir.resolve("app.jar"), appClasses);

        try (InputContainer container = InputContainers.open(appJar, 8);
             ClassSourceResolver resolver = ClassSourceResolver.open(container, false, java.util.List.of(depJar.toString()), 8)) {
            byte[] helperBytes = resolver.load("dep/Helper");
            assertNotNull(helperBytes);
            String classBytes = new String(helperBytes, StandardCharsets.ISO_8859_1);
            assertTrue(classBytes.contains("base"));
            assertTrue(!classBytes.contains("mr17"));
        }
    }
}
