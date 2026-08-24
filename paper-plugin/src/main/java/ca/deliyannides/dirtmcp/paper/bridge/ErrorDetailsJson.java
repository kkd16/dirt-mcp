package ca.deliyannides.dirtmcp.paper.bridge;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Objects;

public final class ErrorDetailsJson {
    private ErrorDetailsJson() {}

    public static String code(ErrorDetails details) {
        Objects.requireNonNull(details, "details");
        return switch (details) {
            case ErrorDetails.InvalidRequest ignored -> "invalid_request";
            case ErrorDetails.ChangeLimitExceeded ignored -> "change_limit_exceeded";
            case ErrorDetails.EditNotFound ignored -> "edit_not_found";
            case ErrorDetails.EditNotLatest ignored -> "edit_not_latest";
            case ErrorDetails.HistoryCapacityExceeded ignored -> "history_capacity_exceeded";
            case ErrorDetails.PlayerNotFound ignored -> "player_not_found";
            case ErrorDetails.PlayerUnavailable ignored -> "player_unavailable";
            case ErrorDetails.RegionTooLarge ignored -> "region_too_large";
            case ErrorDetails.ResultTooLarge ignored -> "result_too_large";
            case ErrorDetails.ServerUnavailable ignored -> "server_unavailable";
            case ErrorDetails.Unhealthy ignored -> "unhealthy";
            case ErrorDetails.WorldBusy ignored -> "world_busy";
            case ErrorDetails.WorldNotFound ignored -> "world_not_found";
            case ErrorDetails.WorldUnavailable ignored -> "world_unavailable";
        };
    }

    public static JsonObject serialize(ErrorDetails details) {
        Objects.requireNonNull(details, "details");
        return switch (details) {
            case ErrorDetails.InvalidRequest.Missing value ->
                    property(reason("missing"), "field", value.field());
            case ErrorDetails.InvalidRequest.InvalidValue value ->
                    property(reason("invalid_value"), "field", value.field());
            case ErrorDetails.InvalidRequest.Duplicate value ->
                    property(reason("duplicate"), "field", value.field());
            case ErrorDetails.InvalidRequest.UnknownFields value ->
                    property(reason("unknown_fields"), "field", value.field());
            case ErrorDetails.InvalidRequest.OutOfRange value -> {
                JsonObject object = property(reason("out_of_range"), "target", value.target());
                object.addProperty("value", value.value());
                object.addProperty("minimum", value.minimum());
                object.addProperty("maximum", value.maximum());
                yield object;
            }
            case ErrorDetails.InvalidRequest.UnsupportedValue value -> {
                JsonObject object = property(reason("unsupported_value"), "target", value.target());
                object.add("allowedValues", strings(value.allowedValues()));
                yield object;
            }
            case ErrorDetails.InvalidRequest.PaletteWeightsMixed value ->
                    property(reason("palette_weights_mixed"), "field", value.field());
            case ErrorDetails.InvalidRequest.PaletteWeightTotal value -> {
                JsonObject object =
                        property(reason("palette_weight_total"), "field", value.field());
                object.addProperty("requested", value.requested());
                object.addProperty("required", 100);
                yield object;
            }
            case ErrorDetails.InvalidRequest.TooManyItems value -> {
                JsonObject object = reason("too_many_items");
                object.add("fields", strings(value.fields()));
                object.addProperty("maximum", value.maximum());
                yield object;
            }
            case ErrorDetails.ChangeLimitExceeded value -> property("maximum", value.maximum());
            case ErrorDetails.EditNotFound value -> {
                JsonObject object = property("world", value.world());
                object.addProperty("requestedEditId", value.requestedEditId().toString());
                yield object;
            }
            case ErrorDetails.EditNotLatest value -> {
                JsonObject object = property("world", value.world());
                object.addProperty("requestedEditId", value.requestedEditId().toString());
                object.addProperty("newestEditId", value.newestEditId().toString());
                yield object;
            }
            case ErrorDetails.HistoryCapacityExceeded.EntriesPerWorld value ->
                    property(reason("entries_per_world"), "maximum", value.maximum());
            case ErrorDetails.HistoryCapacityExceeded.EntriesTotal value ->
                    property(reason("entries_total"), "maximum", value.maximum());
            case ErrorDetails.HistoryCapacityExceeded.RetainedChangedBlocks value ->
                    property(reason("retained_changed_blocks"), "maximum", value.maximum());
            case ErrorDetails.PlayerNotFound value -> property("player", value.player());
            case ErrorDetails.PlayerUnavailable.SpectatingEntity value ->
                    property(reason("spectating_entity"), "player", value.player());
            case ErrorDetails.PlayerUnavailable.NonFiniteState value -> {
                JsonObject object = reason("non_finite_state");
                object.addProperty("player", value.player());
                object.addProperty("field", value.field());
                yield object;
            }
            case ErrorDetails.PlayerUnavailable.PositionOutOfRange value -> {
                JsonObject object = reason("position_out_of_range");
                object.addProperty("player", value.player());
                object.addProperty("field", value.field());
                yield object;
            }
            case ErrorDetails.RegionTooLarge.Volume value -> {
                JsonObject object = reason("volume");
                object.add("dimensions", dimensions(value.dimensions()));
                object.addProperty("maximum", value.maximum());
                yield object;
            }
            case ErrorDetails.RegionTooLarge.TouchedChunks value -> {
                JsonObject object = reason("touched_chunks");
                object.addProperty("minimumRequired", value.minimumRequired());
                object.addProperty("maximum", value.maximum());
                yield object;
            }
            case ErrorDetails.RegionTooLarge.PerspectiveChunks value -> {
                JsonObject object = reason("perspective_chunks");
                object.addProperty("requested", value.requested());
                object.addProperty("maximum", value.maximum());
                yield object;
            }
            case ErrorDetails.RegionTooLarge.BlockCount value -> {
                JsonObject object = reason("block_count");
                object.addProperty("minimumRequired", value.minimumRequired());
                object.addProperty("maximum", value.maximum());
                yield object;
            }
            case ErrorDetails.ResultTooLarge.StructureEntries value ->
                    result("structure_entries", value.minimumRequired(), value.maximum());
            case ErrorDetails.ResultTooLarge.Palettes value ->
                    result("palettes", value.minimumRequired(), value.maximum());
            case ErrorDetails.ResultTooLarge.PerspectiveRays value ->
                    result("perspective_rays", value.minimumRequired(), value.maximum());
            case ErrorDetails.ResultTooLarge.PerspectiveRayDistance value ->
                    result("perspective_ray_distance", value.minimumRequired(), value.maximum());
            case ErrorDetails.ServerUnavailable.DependencyUnavailable ignored ->
                    reason("dependency_unavailable");
            case ErrorDetails.ServerUnavailable.PaperUnavailable ignored ->
                    reason("paper_unavailable");
            case ErrorDetails.ServerUnavailable.InspectionBusy value ->
                    property(
                            reason("inspection_busy"),
                            "maximumConcurrentInspections",
                            value.maximumConcurrentInspections());
            case ErrorDetails.Unhealthy.PluginDisabled ignored -> reason("plugin_disabled");
            case ErrorDetails.Unhealthy.DependencyUnavailable ignored ->
                    reason("dependency_unavailable");
            case ErrorDetails.Unhealthy.NoLoadedWorlds ignored -> reason("no_loaded_worlds");
            case ErrorDetails.Unhealthy.HealthCheckFailed ignored -> reason("health_check_failed");
            case ErrorDetails.Unhealthy.PaperUnavailable ignored -> reason("paper_unavailable");
            case ErrorDetails.WorldBusy.OperationInProgress value ->
                    property(reason("operation_in_progress"), "world", value.world());
            case ErrorDetails.WorldBusy.RecoveryRequired value -> {
                JsonObject object = property(reason("recovery_required"), "world", value.world());
                object.addProperty("newestEditId", value.newestEditId().toString());
                yield object;
            }
            case ErrorDetails.WorldNotFound value -> property("world", value.world());
            case ErrorDetails.WorldUnavailable.Stopping ignored -> reason("stopping");
            case ErrorDetails.WorldUnavailable.Interrupted ignored -> reason("interrupted");
            case ErrorDetails.WorldUnavailable.PaperUnavailable ignored ->
                    reason("paper_unavailable");
            case ErrorDetails.WorldUnavailable.OperationFailed ignored ->
                    reason("operation_failed");
            case ErrorDetails.WorldUnavailable.RolledBack ignored -> reason("rolled_back");
            case ErrorDetails.WorldUnavailable.RollbackFailed ignored -> reason("rollback_failed");
            case ErrorDetails.WorldUnavailable.WorldUnloaded value ->
                    property(reason("world_unloaded"), "world", value.world());
            case ErrorDetails.WorldUnavailable.ChunkUnloaded value ->
                    chunk("chunk_unloaded", value.world(), value.chunk());
            case ErrorDetails.WorldUnavailable.ChunkLoadFailed value ->
                    chunk("chunk_load_failed", value.world(), value.chunk());
        };
    }

    private static JsonObject reason(String reason) {
        return property("reason", reason);
    }

    private static JsonObject property(String name, String value) {
        JsonObject object = new JsonObject();
        object.addProperty(name, value);
        return object;
    }

    private static JsonObject property(String name, long value) {
        JsonObject object = new JsonObject();
        object.addProperty(name, value);
        return object;
    }

    private static JsonObject property(JsonObject object, String name, String value) {
        object.addProperty(name, value);
        return object;
    }

    private static JsonObject property(JsonObject object, String name, long value) {
        object.addProperty(name, value);
        return object;
    }

    private static JsonObject dimensions(ErrorDetails.Dimensions value) {
        JsonObject object = new JsonObject();
        object.addProperty("x", value.x());
        object.addProperty("y", value.y());
        object.addProperty("z", value.z());
        return object;
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray(values.size());
        values.forEach(array::add);
        return array;
    }

    private static JsonObject result(String reason, long minimumRequired, int maximum) {
        JsonObject object = reason(reason);
        object.addProperty("minimumRequired", minimumRequired);
        object.addProperty("maximum", maximum);
        return object;
    }

    private static JsonObject chunk(String reason, String world, ErrorDetails.Chunk chunk) {
        JsonObject object = property(reason(reason), "world", world);
        JsonObject position = new JsonObject();
        position.addProperty("x", chunk.x());
        position.addProperty("z", chunk.z());
        object.add("chunk", position);
        return object;
    }
}
