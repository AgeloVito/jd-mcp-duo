package tools;

import decompile.EngineCatalog;
import model.MCPTool;
import model.ToolResult;
import support.SchemaSupport;
import support.ToolResults;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class ListEnginesTool implements MCPTool {
    @Override
    public String getDescription() {
        return "List available decompiler engines and aliases.";
    }

    @Override
    public JsonObject getInputSchema() {
        return SchemaSupport.objectSchema();
    }

    @Override
    public ToolResult execute(JsonObject arguments) {
        JsonObject structured = new JsonObject();
        structured.add("engines", enginesWithAuto());
        JsonArray profiles = new JsonArray();
        JsonObject fastProfile = new JsonObject();
        fastProfile.addProperty("profile", "fast");
        fastProfile.addProperty("description", "Auto: Vineflower → CFR → JD-Core v1+v0 patch → JADX.");
        profiles.add(fastProfile);
        JsonObject accurateProfile = new JsonObject();
        accurateProfile.addProperty("profile", "accurate");
        accurateProfile.addProperty("description", "Auto: Vineflower → CFR → JD-Core v1+v0 patch → JADX.");
        profiles.add(accurateProfile);
        JsonObject debuggableProfile = new JsonObject();
        debuggableProfile.addProperty("profile", "debuggable");
        debuggableProfile.addProperty("description", "Auto: Vineflower → CFR → JD-Core v1+v0 patch → JADX. lineNumbers defaults to true.");
        profiles.add(debuggableProfile);
        structured.add("profiles", profiles);
        structured.add("aliases", EngineCatalog.aliasesJson());
        structured.addProperty("defaultEngine", "auto");
        return ToolResults.structured(renderText(structured), structured);
    }

    private static JsonArray enginesWithAuto() {
        JsonArray engines = new JsonArray();
        JsonObject auto = new JsonObject();
        auto.addProperty("engine", "auto");
        auto.addProperty("description", "Automatic selection with JD-Core v1/v0 patching and ordered fallback engines.");
        JsonArray profiles = new JsonArray();
        profiles.add("fast");
        profiles.add("accurate");
        profiles.add("debuggable");
        auto.add("profiles", profiles);
        JsonArray aliases = new JsonArray();
        aliases.add("auto");
        auto.add("aliases", aliases);
        engines.add(auto);
        EngineCatalog.enginesJson().forEach(engines::add);
        return engines;
    }

    private String renderText(JsonObject structured) {
        StringBuilder text = new StringBuilder();
        text.append("Available decompiler engines:\n");

        JsonArray engines = structured.getAsJsonArray("engines");
        for (JsonElement element : engines) {
            JsonObject engine = element.getAsJsonObject();
            text.append("- ")
                    .append(engine.get("engine").getAsString())
                    .append(": ")
                    .append(engine.get("description").getAsString());
            JsonArray profiles = engine.getAsJsonArray("profiles");
            if (profiles != null && !profiles.isEmpty()) {
                text.append(" (profiles: ");
                for (int i = 0; i < profiles.size(); i++) {
                    if (i > 0) {
                        text.append(", ");
                    }
                    text.append(profiles.get(i).getAsString());
                }
                text.append(")");
            }
            text.append('\n');
        }

        text.append("\nAliases:\n");
        JsonObject aliases = structured.getAsJsonObject("aliases");
        aliases.entrySet().forEach(entry -> text.append("- ")
                .append(entry.getKey())
                .append(" -> ")
                .append(entry.getValue().getAsString())
                .append('\n'));

        text.append("\nProfiles:\n");
        JsonArray profiles = structured.getAsJsonArray("profiles");
        for (JsonElement element : profiles) {
            JsonObject profile = element.getAsJsonObject();
            text.append("- ")
                    .append(profile.get("profile").getAsString())
                    .append(": ")
                    .append(profile.get("description").getAsString())
                    .append('\n');
        }

        return text.toString().trim();
    }
}
