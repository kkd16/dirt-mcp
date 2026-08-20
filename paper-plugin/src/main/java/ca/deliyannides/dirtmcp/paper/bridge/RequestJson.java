package ca.deliyannides.dirtmcp.paper.bridge;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public final class RequestJson {
    private static final Set<String> POSITION_FIELDS = Set.of("x", "y", "z");

    private RequestJson() {}

    public static String string(JsonElement element, String name) throws OperationException {
        if (!(element instanceof JsonPrimitive primitive)
                || !primitive.isString()
                || primitive.getAsString().isBlank()) {
            throw invalid(
                    name + " must be a non-empty string",
                    new ErrorDetails.InvalidRequest.InvalidValue(name));
        }
        return primitive.getAsString();
    }

    public static boolean bool(JsonElement element, String name) throws OperationException {
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isBoolean()) {
            throw invalid(
                    name + " must be a boolean",
                    new ErrorDetails.InvalidRequest.InvalidValue(name));
        }
        return primitive.getAsBoolean();
    }

    public static int integer(JsonElement element, String name) throws OperationException {
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
            throw invalid(
                    name + " must be a signed 32-bit integer",
                    new ErrorDetails.InvalidRequest.InvalidValue(name));
        }
        try {
            return primitive.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw invalid(
                    name + " must be a signed 32-bit integer",
                    new ErrorDetails.InvalidRequest.InvalidValue(name));
        }
    }

    public static BlockPosition position(JsonElement element, String name)
            throws OperationException {
        if (element == null || !element.isJsonObject()) {
            throw invalid(
                    name + " must be an object",
                    new ErrorDetails.InvalidRequest.InvalidValue(name));
        }
        JsonObject object = element.getAsJsonObject();
        requireExactFields(object, POSITION_FIELDS, name);
        return new BlockPosition(
                integer(object.get("x"), name + ".x"),
                integer(object.get("y"), name + ".y"),
                integer(object.get("z"), name + ".z"));
    }

    public static List<String> stringList(JsonElement element, String name)
            throws OperationException {
        if (element == null || !element.isJsonArray()) {
            throw invalid(
                    name + " must be an array of non-empty strings",
                    new ErrorDetails.InvalidRequest.InvalidValue(name));
        }
        List<String> values = new ArrayList<>(element.getAsJsonArray().size());
        for (int index = 0; index < element.getAsJsonArray().size(); index++) {
            JsonElement value = element.getAsJsonArray().get(index);
            if (!(value instanceof JsonPrimitive primitive)
                    || !primitive.isString()
                    || primitive.getAsString().isBlank()) {
                throw invalid(
                        name + "[] must be a non-empty string",
                        new ErrorDetails.InvalidRequest.InvalidValue(name + "[" + index + "]"));
            }
            values.add(primitive.getAsString());
        }
        return List.copyOf(values);
    }

    public static List<String> nonEmptyStringList(JsonElement element, String name)
            throws OperationException {
        List<String> values = stringList(element, name);
        if (values.isEmpty()) {
            throw invalid(
                    name + " must contain at least one entry",
                    new ErrorDetails.InvalidRequest.InvalidValue(name));
        }
        return values;
    }

    public static void requireExactFields(JsonObject object, Set<String> expected, String name)
            throws OperationException {
        if (!object.keySet().equals(expected)) {
            throw invalid(
                    name + " contains missing or unknown fields",
                    fieldSetDetails(object, expected, expected, name));
        }
    }

    public static void requireFields(
            JsonObject object, Set<String> required, Set<String> allowed, String name)
            throws OperationException {
        if (!object.keySet().containsAll(required) || !allowed.containsAll(object.keySet())) {
            throw invalid(
                    "Request contains missing or unknown fields",
                    fieldSetDetails(object, required, allowed, name));
        }
    }

    public static OperationException invalid(String message, ErrorDetails.InvalidRequest details) {
        return new OperationException(OperationFailure.INVALID_REQUEST, message, details);
    }

    private static ErrorDetails.InvalidRequest fieldSetDetails(
            JsonObject object, Set<String> required, Set<String> allowed, String name) {
        String missing =
                required.stream()
                        .filter(field -> !object.has(field))
                        .min(Comparator.naturalOrder())
                        .orElse(null);
        if (missing != null) {
            return new ErrorDetails.InvalidRequest.Missing(field(name, missing));
        }
        String unknown =
                object.keySet().stream()
                        .filter(field -> !allowed.contains(field))
                        .min(Comparator.naturalOrder())
                        .orElseThrow();
        return new ErrorDetails.InvalidRequest.UnknownFields(field(name, unknown));
    }

    private static String field(String parent, String child) {
        return "Request".equals(parent) ? child : parent + "." + child;
    }
}
