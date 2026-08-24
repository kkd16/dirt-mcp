package ca.deliyannides.dirtmcp.paper.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class ErrorDetailsJsonTest {
    private static final UUID REQUESTED_EDIT_ID =
            UUID.fromString("123e4567-e89b-42d3-a456-426614174000");
    private static final UUID NEWEST_EDIT_ID =
            UUID.fromString("123e4567-e89b-42d3-a456-426614174001");

    @ParameterizedTest
    @MethodSource("details")
    void serializesStrictWireDetails(ErrorDetails details, String expected) {
        assertEquals(JsonParser.parseString(expected), ErrorDetailsJson.serialize(details));
    }

    @ParameterizedTest
    @MethodSource("codes")
    void selectsTheMatchingTopLevelCode(ErrorDetails details, String expected) {
        assertEquals(expected, ErrorDetailsJson.code(details));
    }

    @Test
    void addsAnIndexedListFieldWithoutChangingTheHumanMessage() {
        OperationException failure =
                assertThrows(
                        OperationException.class,
                        () ->
                                RequestJson.stringList(
                                        JsonParser.parseString("[\"valid\",null]"), "patterns"));

        assertEquals("patterns[] must be a string", failure.getMessage());
        assertEquals(
                new ErrorDetails.InvalidRequest.InvalidValue("patterns[1]"),
                failure.details().orElseThrow());
    }

    @Test
    void rejectsUnknownNonFinitePlayerStateFields() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ErrorDetails.PlayerUnavailable.NonFiniteState(
                                "Builder", "vitals.unknown"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ErrorDetails.PlayerUnavailable.PositionOutOfRange(
                                "Builder", "perspectiveEndpoint.unknown"));
    }

    @Test
    void boundsPlayerSelectorsInOutboundErrorDetails() {
        assertEquals("x".repeat(36), new ErrorDetails.PlayerNotFound("x".repeat(36)).player());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ErrorDetails.PlayerNotFound("x".repeat(37)));
    }

    private static Stream<Arguments> details() {
        return Stream.of(
                detail(
                        new ErrorDetails.InvalidRequest.Missing("world"),
                        "{reason:'missing',field:'world'}"),
                detail(
                        new ErrorDetails.InvalidRequest.InvalidValue("world"),
                        "{reason:'invalid_value',field:'world'}"),
                detail(
                        new ErrorDetails.InvalidRequest.Duplicate("placements[1]"),
                        "{reason:'duplicate',field:'placements[1]'}"),
                detail(
                        new ErrorDetails.InvalidRequest.UnknownFields("extra"),
                        "{reason:'unknown_fields',field:'extra'}"),
                detail(
                        new ErrorDetails.InvalidRequest.OutOfRange("maxResults", 101, 1, 100),
                        "{reason:'out_of_range',target:'maxResults',value:101,minimum:1,maximum:100}"),
                detail(
                        new ErrorDetails.InvalidRequest.UnsupportedValue(
                                "strategy", List.of("first", "nearest")),
                        "{reason:'unsupported_value',target:'strategy',"
                                + "allowedValues:['first','nearest']}"),
                detail(
                        new ErrorDetails.InvalidRequest.PaletteWeightsMixed("destinationPalette"),
                        "{reason:'palette_weights_mixed',field:'destinationPalette'}"),
                detail(
                        new ErrorDetails.InvalidRequest.PaletteWeightTotal(
                                "destinationPalette", 90),
                        "{reason:'palette_weight_total',field:'destinationPalette',"
                                + "requested:90,required:100}"),
                detail(
                        new ErrorDetails.InvalidRequest.TooManyItems(
                                List.of("includeBlockStatePatterns", "excludeBlockStatePatterns"),
                                64),
                        "{reason:'too_many_items',fields:['includeBlockStatePatterns',"
                                + "'excludeBlockStatePatterns'],maximum:64}"),
                detail(new ErrorDetails.ChangeLimitExceeded(1_000), "{maximum:1000}"),
                detail(
                        new ErrorDetails.EditNotFound("world", REQUESTED_EDIT_ID),
                        "{world:'world',requestedEditId:'" + REQUESTED_EDIT_ID + "'}"),
                detail(
                        new ErrorDetails.EditNotLatest("world", REQUESTED_EDIT_ID, NEWEST_EDIT_ID),
                        "{world:'world',requestedEditId:'"
                                + REQUESTED_EDIT_ID
                                + "',newestEditId:'"
                                + NEWEST_EDIT_ID
                                + "'}"),
                detail(
                        new ErrorDetails.HistoryCapacityExceeded.EntriesPerWorld(10),
                        "{reason:'entries_per_world',maximum:10}"),
                detail(
                        new ErrorDetails.HistoryCapacityExceeded.EntriesTotal(20),
                        "{reason:'entries_total',maximum:20}"),
                detail(
                        new ErrorDetails.HistoryCapacityExceeded.RetainedChangedBlocks(30),
                        "{reason:'retained_changed_blocks',maximum:30}"),
                detail(
                        new ErrorDetails.RegionTooLarge.Volume(
                                new ErrorDetails.Dimensions(2, 3, 4), 20),
                        "{reason:'volume',dimensions:{x:2,y:3,z:4},maximum:20}"),
                detail(
                        new ErrorDetails.RegionTooLarge.TouchedChunks(9, 8),
                        "{reason:'touched_chunks',minimumRequired:9,maximum:8}"),
                detail(
                        new ErrorDetails.RegionTooLarge.PerspectiveChunks(9, 8),
                        "{reason:'perspective_chunks',requested:9,maximum:8}"),
                detail(
                        new ErrorDetails.RegionTooLarge.BlockCount(9, 8),
                        "{reason:'block_count',minimumRequired:9,maximum:8}"),
                detail(
                        new ErrorDetails.ResultTooLarge.StructureEntries(9, 8),
                        "{reason:'structure_entries',minimumRequired:9,maximum:8}"),
                detail(
                        new ErrorDetails.ResultTooLarge.Palettes(9, 8),
                        "{reason:'palettes',minimumRequired:9,maximum:8}"),
                detail(
                        new ErrorDetails.ResultTooLarge.PerspectiveRays(9, 8),
                        "{reason:'perspective_rays',minimumRequired:9,maximum:8}"),
                detail(
                        new ErrorDetails.ResultTooLarge.PerspectiveRayDistance(9, 8),
                        "{reason:'perspective_ray_distance',minimumRequired:9,maximum:8}"),
                detail(new ErrorDetails.PlayerNotFound("Builder"), "{player:'Builder'}"),
                detail(
                        new ErrorDetails.PlayerUnavailable.SpectatingEntity("Builder"),
                        "{reason:'spectating_entity',player:'Builder'}"),
                detail(
                        new ErrorDetails.PlayerUnavailable.NonFiniteState(
                                "Builder", "vitals.saturation"),
                        "{reason:'non_finite_state',player:'Builder',field:'vitals.saturation'}"),
                detail(
                        new ErrorDetails.PlayerUnavailable.PositionOutOfRange(
                                "Builder", "perspectiveEndpoint.x"),
                        "{reason:'position_out_of_range',player:'Builder',field:'perspectiveEndpoint.x'}"),
                detail(
                        new ErrorDetails.ServerUnavailable.DependencyUnavailable(),
                        "{reason:'dependency_unavailable'}"),
                detail(
                        new ErrorDetails.ServerUnavailable.PaperUnavailable(),
                        "{reason:'paper_unavailable'}"),
                detail(
                        new ErrorDetails.ServerUnavailable.InspectionBusy(2),
                        "{reason:'inspection_busy',maximumConcurrentInspections:2}"),
                detail(new ErrorDetails.Unhealthy.PluginDisabled(), "{reason:'plugin_disabled'}"),
                detail(
                        new ErrorDetails.Unhealthy.DependencyUnavailable(),
                        "{reason:'dependency_unavailable'}"),
                detail(new ErrorDetails.Unhealthy.NoLoadedWorlds(), "{reason:'no_loaded_worlds'}"),
                detail(
                        new ErrorDetails.Unhealthy.HealthCheckFailed(),
                        "{reason:'health_check_failed'}"),
                detail(
                        new ErrorDetails.Unhealthy.PaperUnavailable(),
                        "{reason:'paper_unavailable'}"),
                detail(
                        new ErrorDetails.WorldBusy.OperationInProgress("world"),
                        "{reason:'operation_in_progress',world:'world'}"),
                detail(
                        new ErrorDetails.WorldBusy.RecoveryRequired("world", NEWEST_EDIT_ID),
                        "{reason:'recovery_required',world:'world',newestEditId:'"
                                + NEWEST_EDIT_ID
                                + "'}"),
                detail(new ErrorDetails.WorldNotFound("world"), "{world:'world'}"),
                detail(new ErrorDetails.WorldUnavailable.Stopping(), "{reason:'stopping'}"),
                detail(new ErrorDetails.WorldUnavailable.Interrupted(), "{reason:'interrupted'}"),
                detail(
                        new ErrorDetails.WorldUnavailable.PaperUnavailable(),
                        "{reason:'paper_unavailable'}"),
                detail(
                        new ErrorDetails.WorldUnavailable.OperationFailed(),
                        "{reason:'operation_failed'}"),
                detail(new ErrorDetails.WorldUnavailable.RolledBack(), "{reason:'rolled_back'}"),
                detail(
                        new ErrorDetails.WorldUnavailable.RollbackFailed(),
                        "{reason:'rollback_failed'}"),
                detail(
                        new ErrorDetails.WorldUnavailable.WorldUnloaded("world"),
                        "{reason:'world_unloaded',world:'world'}"),
                detail(
                        new ErrorDetails.WorldUnavailable.ChunkUnloaded(
                                "world", new ErrorDetails.Chunk(-2, 3)),
                        "{reason:'chunk_unloaded',world:'world',chunk:{x:-2,z:3}}"),
                detail(
                        new ErrorDetails.WorldUnavailable.ChunkLoadFailed(
                                "world", new ErrorDetails.Chunk(-2, 3)),
                        "{reason:'chunk_load_failed',world:'world',chunk:{x:-2,z:3}}"));
    }

    private static Stream<Arguments> codes() {
        return Stream.of(
                Arguments.of(
                        new ErrorDetails.InvalidRequest.InvalidValue("field"), "invalid_request"),
                Arguments.of(new ErrorDetails.ChangeLimitExceeded(1), "change_limit_exceeded"),
                Arguments.of(
                        new ErrorDetails.EditNotFound("world", REQUESTED_EDIT_ID),
                        "edit_not_found"),
                Arguments.of(
                        new ErrorDetails.EditNotLatest("world", REQUESTED_EDIT_ID, NEWEST_EDIT_ID),
                        "edit_not_latest"),
                Arguments.of(
                        new ErrorDetails.HistoryCapacityExceeded.EntriesPerWorld(1),
                        "history_capacity_exceeded"),
                Arguments.of(new ErrorDetails.PlayerNotFound("Builder"), "player_not_found"),
                Arguments.of(
                        new ErrorDetails.PlayerUnavailable.SpectatingEntity("Builder"),
                        "player_unavailable"),
                Arguments.of(
                        new ErrorDetails.PlayerUnavailable.NonFiniteState(
                                "Builder", "movement.fallDistance"),
                        "player_unavailable"),
                Arguments.of(
                        new ErrorDetails.PlayerUnavailable.PositionOutOfRange(
                                "Builder", "feetPosition.x"),
                        "player_unavailable"),
                Arguments.of(new ErrorDetails.RegionTooLarge.BlockCount(2, 1), "region_too_large"),
                Arguments.of(
                        new ErrorDetails.ResultTooLarge.StructureEntries(2, 1), "result_too_large"),
                Arguments.of(
                        new ErrorDetails.ServerUnavailable.PaperUnavailable(),
                        "server_unavailable"),
                Arguments.of(new ErrorDetails.Unhealthy.PluginDisabled(), "unhealthy"),
                Arguments.of(new ErrorDetails.WorldBusy.OperationInProgress("world"), "world_busy"),
                Arguments.of(new ErrorDetails.WorldNotFound("world"), "world_not_found"),
                Arguments.of(
                        new ErrorDetails.WorldUnavailable.WorldUnloaded("world"),
                        "world_unavailable"));
    }

    private static Arguments detail(ErrorDetails details, String json) {
        return Arguments.of(details, json);
    }
}
