package tools;

import model.MCPTool;
import model.ToolResult;
import support.JsonUtils;
import support.ToolResults;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

public class CompareJarsTool implements MCPTool {
    @Override
    public String getDescription() {
        return "Compare archive entries by size and CRC and summarize added, removed, and modified classes.";
    }

    @Override
    public JsonObject getInputSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        addStringProperty(properties, "jar1", "First archive path");
        addStringProperty(properties, "jar2", "Second archive path");
        JsonObject detail = new JsonObject();
        detail.addProperty("type", "boolean");
        detail.addProperty("default", true);
        properties.add("detail", detail);
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("jar1");
        required.add("jar2");
        schema.add("required", required);
        return schema;
    }

    @Override
    public ToolResult execute(JsonObject arguments) throws Exception {
        Path left = JsonUtils.getRequiredPath(arguments, "jar1");
        Path right = JsonUtils.getRequiredPath(arguments, "jar2");
        boolean detail = JsonUtils.getBoolean(arguments, "detail", true);
        if (!Files.exists(left)) {
            throw new IOException("Archive not found: " + left);
        }
        if (!Files.exists(right)) {
            throw new IOException("Archive not found: " + right);
        }

        Map<String, EntryInfo> leftEntries = readEntries(left);
        Map<String, EntryInfo> rightEntries = readEntries(right);
        Set<String> allEntries = new HashSet<>();
        allEntries.addAll(leftEntries.keySet());
        allEntries.addAll(rightEntries.keySet());

        JsonArray added = new JsonArray();
        JsonArray removed = new JsonArray();
        JsonArray modified = new JsonArray();
        int unchanged = 0;

        for (String entry : allEntries) {
            EntryInfo l = leftEntries.get(entry);
            EntryInfo r = rightEntries.get(entry);
            if (l == null) {
                added.add(entry);
            } else if (r == null) {
                removed.add(entry);
            } else if (l.size != r.size || l.crc != r.crc) {
                JsonObject json = new JsonObject();
                json.addProperty("entry", entry);
                json.addProperty("leftSize", l.size);
                json.addProperty("rightSize", r.size);
                json.addProperty("leftCrc", l.crc);
                json.addProperty("rightCrc", r.crc);
                modified.add(json);
            } else {
                unchanged++;
            }
        }

        StringBuilder text = new StringBuilder();
        text.append("Comparison\n");
        text.append("Left: ").append(left).append('\n');
        text.append("Right: ").append(right).append('\n');
        text.append("Unchanged: ").append(unchanged).append('\n');
        text.append("Added: ").append(added.size()).append('\n');
        text.append("Removed: ").append(removed.size()).append('\n');
        text.append("Modified: ").append(modified.size()).append('\n');
        if (detail) {
            appendEntries(text, "Added", added);
            appendEntries(text, "Removed", removed);
            appendModified(text, modified);
        }

        JsonObject structured = new JsonObject();
        structured.addProperty("left", left.toString());
        structured.addProperty("right", right.toString());
        structured.addProperty("unchanged", unchanged);
        structured.add("added", added);
        structured.add("removed", removed);
        structured.add("modified", modified);
        return ToolResults.structured(text.toString().trim(), structured);
    }

    private static void appendEntries(StringBuilder text, String label, JsonArray entries) {
        if (entries.isEmpty()) {
            return;
        }
        text.append('\n').append(label).append(":\n");
        for (int i = 0; i < Math.min(entries.size(), 20); i++) {
            text.append("  - ").append(entries.get(i).getAsString()).append('\n');
        }
    }

    private static void appendModified(StringBuilder text, JsonArray entries) {
        if (entries.isEmpty()) {
            return;
        }
        text.append("\nModified:\n");
        for (int i = 0; i < Math.min(entries.size(), 20); i++) {
            JsonObject entry = entries.get(i).getAsJsonObject();
            text.append("  - ").append(entry.get("entry").getAsString())
                    .append(" (").append(entry.get("leftSize").getAsLong())
                    .append(" -> ").append(entry.get("rightSize").getAsLong())
                    .append(")\n");
        }
    }

    private static Map<String, EntryInfo> readEntries(Path path) throws IOException {
        Map<String, EntryInfo> entries = new HashMap<>();
        try (JarFile jarFile = new JarFile(path.toFile())) {
            jarFile.stream().forEach(entry -> entries.put(entry.getName(), new EntryInfo(entry.getSize(), entry.getCrc())));
        }
        return entries;
    }

    private static void addStringProperty(JsonObject properties, String name, String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "string");
        property.addProperty("description", description);
        properties.add(name, property);
    }

    private record EntryInfo(long size, long crc) {
    }
}
