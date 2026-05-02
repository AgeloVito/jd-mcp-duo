package support;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class RepositoryConfigSupport {
    private RepositoryConfigSupport() {
    }

    public static JsonObject mergeWithConfig(JsonObject arguments) throws IOException {
        JsonObject merged = arguments == null ? new JsonObject() : arguments.deepCopy();
        String configPathText = JsonUtils.getString(merged, "configPath", null);
        if (configPathText == null || configPathText.isBlank()) {
            return merged;
        }
        Path configPath = Path.of(configPathText).toAbsolutePath().normalize();
        if (!Files.exists(configPath)) {
            throw new IOException("configPath not found: " + configPath);
        }

        JsonObject config = parseConfig(configPath);
        for (String key : config.keySet()) {
            if (!merged.has(key)) {
                merged.add(key, config.get(key));
            }
        }
        return merged;
    }

    private static JsonObject parseConfig(Path configPath) throws IOException {
        String fileName = configPath.getFileName().toString().toLowerCase();
        String content = Files.readString(configPath);
        if (fileName.endsWith(".json")) {
            JsonElement parsed = JsonParser.parseString(content);
            if (!parsed.isJsonObject()) {
                throw new IOException("Repository config must be a JSON object: " + configPath);
            }
            return parsed.getAsJsonObject();
        }

        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(configPath)) {
            properties.load(reader);
        }
        JsonObject config = new JsonObject();
        properties.forEach((key, value) -> config.addProperty(String.valueOf(key), String.valueOf(value)));
        return config;
    }
}
