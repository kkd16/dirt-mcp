package ca.deliyannides.dirtmcp.paper.catalog;

import ca.deliyannides.dirtmcp.paper.access.AccessControl.AccessProfile;
import ca.deliyannides.dirtmcp.paper.bridge.BridgeOperation;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Validated human-facing MCP catalog shared with the web service. */
public final class ToolCatalog {
    private static final Set<String> ROOT_FIELDS = Set.of("version", "tools");
    private static final Set<String> TOOL_FIELDS =
            Set.of(
                    "name",
                    "operationId",
                    "category",
                    "title",
                    "minimumProfile",
                    "changesWorld",
                    "description",
                    "useWhen",
                    "inputs",
                    "returns",
                    "caution",
                    "exampleInput");

    private final List<Entry> entries;
    private final Map<String, Entry> byName;

    private ToolCatalog(List<Entry> entries) {
        this.entries = List.copyOf(entries);
        Map<String, Entry> names = new HashMap<>();
        EnumSet<BridgeOperation> operations = EnumSet.noneOf(BridgeOperation.class);
        for (Entry entry : entries) {
            if (names.put(entry.name(), entry) != null || !operations.add(entry.operation())) {
                throw invalid();
            }
        }
        if (operations.size() != BridgeOperation.values().length) {
            throw invalid();
        }
        this.byName = Map.copyOf(names);
    }

    public static ToolCatalog load() {
        try (InputStream input = ToolCatalog.class.getResourceAsStream("/dirt-tool-catalog.json")) {
            if (input == null) {
                throw new IllegalStateException("Dirt tool catalog resource is missing");
            }
            JsonElement parsed =
                    JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw invalid();
            }
            JsonObject root = parsed.getAsJsonObject();
            requireFields(root, ROOT_FIELDS);
            if (root.get("version").getAsInt() != 1 || !root.get("tools").isJsonArray()) {
                throw invalid();
            }
            JsonArray tools = root.getAsJsonArray("tools");
            List<Entry> entries = new ArrayList<>(tools.size());
            for (JsonElement tool : tools) {
                if (!tool.isJsonObject()) {
                    throw invalid();
                }
                entries.add(entry(tool.getAsJsonObject()));
            }
            return new ToolCatalog(entries);
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof IllegalStateException illegalState) {
                throw illegalState;
            }
            throw new IllegalStateException("Dirt tool catalog could not be loaded", exception);
        }
    }

    public List<Entry> entries() {
        return this.entries;
    }

    public Entry byName(String name) {
        return this.byName.get(name);
    }

    private static Entry entry(JsonObject object) {
        requireFields(object, TOOL_FIELDS);
        String name = string(object, "name");
        if (!name.matches("[a-z][a-z0-9_]*")) {
            throw invalid();
        }
        BridgeOperation operation = BridgeOperation.parse(string(object, "operationId"));
        String category = string(object, "category");
        if (!Set.of("status", "inspection", "editing", "commands").contains(category)) {
            throw invalid();
        }
        JsonElement changesWorld = object.get("changesWorld");
        if (!changesWorld.isJsonPrimitive() || !changesWorld.getAsJsonPrimitive().isBoolean()) {
            throw invalid();
        }
        if (object.get("exampleInput") == null || object.get("exampleInput").isJsonNull()) {
            throw invalid();
        }
        return new Entry(
                name,
                operation,
                category,
                string(object, "title"),
                accessProfile(string(object, "minimumProfile")),
                changesWorld.getAsBoolean(),
                string(object, "description"),
                string(object, "useWhen"),
                string(object, "inputs"),
                string(object, "returns"),
                string(object, "caution"));
    }

    private static String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {
            throw invalid();
        }
        return value.getAsString();
    }

    private static AccessProfile accessProfile(String value) {
        return switch (value) {
            case "viewer" -> AccessProfile.VIEWER;
            case "builder" -> AccessProfile.BUILDER;
            case "operator" -> AccessProfile.OPERATOR;
            default -> throw invalid();
        };
    }

    private static void requireFields(JsonObject object, Set<String> expected) {
        if (!object.keySet().equals(expected)) {
            throw invalid();
        }
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("Dirt tool catalog is invalid");
    }

    public record Entry(
            String name,
            BridgeOperation operation,
            String category,
            String title,
            AccessProfile minimumProfile,
            boolean changesWorld,
            String description,
            String useWhen,
            String inputs,
            String returns,
            String caution) {
        public Entry {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(minimumProfile, "minimumProfile");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(useWhen, "useWhen");
            Objects.requireNonNull(inputs, "inputs");
            Objects.requireNonNull(returns, "returns");
            Objects.requireNonNull(caution, "caution");
        }
    }
}
