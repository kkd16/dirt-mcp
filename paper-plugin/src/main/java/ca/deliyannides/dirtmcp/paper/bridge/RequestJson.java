package ca.deliyannides.dirtmcp.paper.bridge;

import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class RequestJson {
    private static final Set<String> POSITION_FIELDS = Set.of("x", "y", "z");

    private RequestJson() {}

    public static String string(JsonElement element, String name) throws InvalidRequestException {
        if (!(element instanceof JsonPrimitive primitive)
                || !primitive.isString()
                || primitive.getAsString().isBlank()) {
            throw new InvalidRequestException(name + " must be a non-empty string");
        }
        return primitive.getAsString();
    }

    public static boolean bool(JsonElement element, String name) throws InvalidRequestException {
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isBoolean()) {
            throw new InvalidRequestException(name + " must be a boolean");
        }
        return primitive.getAsBoolean();
    }

    public static int integer(JsonElement element, String name) throws InvalidRequestException {
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
            throw new InvalidRequestException(name + " must be a signed 32-bit integer");
        }
        try {
            return primitive.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new InvalidRequestException(name + " must be a signed 32-bit integer");
        }
    }

    public static BlockPosition position(JsonElement element, String name)
            throws InvalidRequestException {
        if (element == null || !element.isJsonObject()) {
            throw new InvalidRequestException(name + " must be an object");
        }
        JsonObject object = element.getAsJsonObject();
        requireExactFields(object, POSITION_FIELDS, name);
        return new BlockPosition(
                integer(object.get("x"), name + ".x"),
                integer(object.get("y"), name + ".y"),
                integer(object.get("z"), name + ".z"));
    }

    public static List<String> stringList(JsonElement element, String name)
            throws InvalidRequestException {
        if (element == null || !element.isJsonArray()) {
            throw new InvalidRequestException(name + " must be an array of non-empty strings");
        }
        List<String> values = new ArrayList<>(element.getAsJsonArray().size());
        for (JsonElement value : element.getAsJsonArray()) {
            values.add(string(value, name + "[]"));
        }
        return List.copyOf(values);
    }

    public static List<String> nonEmptyStringList(JsonElement element, String name)
            throws InvalidRequestException {
        List<String> values = stringList(element, name);
        if (values.isEmpty()) {
            throw new InvalidRequestException(name + " must contain at least one entry");
        }
        return values;
    }

    public static void requireExactFields(JsonObject object, Set<String> expected, String name)
            throws InvalidRequestException {
        if (!object.keySet().equals(expected)) {
            throw new InvalidRequestException(name + " contains missing or unknown fields");
        }
    }

    public static void requireFields(JsonObject object, Set<String> required, Set<String> allowed)
            throws InvalidRequestException {
        if (!object.keySet().containsAll(required) || !allowed.containsAll(object.keySet())) {
            throw new InvalidRequestException("Request contains missing or unknown fields");
        }
    }
}
