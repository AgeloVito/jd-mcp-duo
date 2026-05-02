package support;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public final class GraphSupport {
    private GraphSupport() {
    }

    public static JsonObject graphMeta(String direction, int depth, int maxNodes) {
        JsonObject json = new JsonObject();
        json.addProperty("direction", direction);
        json.addProperty("depth", depth);
        json.addProperty("maxNodes", maxNodes);
        json.addProperty("truncated", false);
        return json;
    }

    public static boolean consumeNode(AtomicInteger remaining, JsonObject rootMeta, JsonObject node) {
        if (remaining.get() <= 0) {
            node.addProperty("truncated", true);
            rootMeta.addProperty("truncated", true);
            return false;
        }
        remaining.decrementAndGet();
        return true;
    }

    public static void appendIndented(StringBuilder text, int indent, String line) {
        text.append("  ".repeat(Math.max(0, indent))).append("- ").append(line).append('\n');
    }

    public static <T> boolean seen(Set<T> path, T item) {
        return path.contains(item);
    }

    public static JsonArray candidateDescriptors(Iterable<String> descriptors) {
        JsonArray array = new JsonArray();
        for (String descriptor : descriptors) {
            array.add(descriptor);
        }
        return array;
    }
}
