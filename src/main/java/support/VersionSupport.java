package support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

public final class VersionSupport {
    private static final String POM_PROPERTIES = "META-INF/maven/io.github.agelovito/jd-mcp-duo/pom.properties";
    private static final String UNKNOWN = "unknown";

    private VersionSupport() {
    }

    public static String readVersion() {
        try (InputStream inputStream = VersionSupport.class.getClassLoader().getResourceAsStream(POM_PROPERTIES)) {
            if (inputStream == null) {
                return UNKNOWN;
            }
            Properties properties = new Properties();
            properties.load(inputStream);
            return properties.getProperty("version", UNKNOWN);
        } catch (IOException e) {
            return UNKNOWN;
        }
    }
}
