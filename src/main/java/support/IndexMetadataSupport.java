package support;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import index.IndexFailure;
import sqlite.PersistentScopeIndex;

public final class IndexMetadataSupport {
    private IndexMetadataSupport() {
    }

    public static void addIndexFailureMetadata(JsonObject structured, PersistentScopeIndex scope) {
        structured.addProperty("indexFailureCount", scope.indexFailureCount());
        JsonArray failures = new JsonArray();
        for (IndexFailure failure : scope.indexFailures()) {
            JsonObject item = new JsonObject();
            item.addProperty("sourcePath", failure.sourcePath().toString());
            item.addProperty("errorType", failure.errorType());
            item.addProperty("message", failure.message());
            failures.add(item);
        }
        structured.add("indexFailures", failures);
    }
}
