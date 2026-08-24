package ca.deliyannides.dirtmcp.paper.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class OperationExceptionTest {
    @Test
    void requiresAJsonSchemaCompatibleMessage() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        assertEquals(
                                OperationFailure.INTERNAL_ERROR,
                                new OperationException(OperationFailure.INTERNAL_ERROR, null, null)
                                        .failure()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        assertEquals(
                                OperationFailure.INTERNAL_ERROR,
                                new OperationException(OperationFailure.INTERNAL_ERROR, "", null)
                                        .failure()));
        assertEquals(
                " ",
                new OperationException(OperationFailure.INTERNAL_ERROR, " ", null).getMessage());
    }

    @Test
    void requiresDetailsForNonInternalFailuresAndRejectsMismatches() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        assertEquals(
                                OperationFailure.INVALID_REQUEST,
                                new OperationException(
                                                OperationFailure.INVALID_REQUEST, "invalid", null)
                                        .failure()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        assertEquals(
                                OperationFailure.INVALID_REQUEST,
                                new OperationException(
                                                OperationFailure.INVALID_REQUEST,
                                                "invalid",
                                                new ErrorDetails.WorldNotFound("world"))
                                        .failure()));
    }

    @Test
    void requiresInternalFailuresToOmitDetails() {
        OperationException internal =
                new OperationException(OperationFailure.INTERNAL_ERROR, "internal", null);

        assertTrue(internal.details().isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        assertEquals(
                                OperationFailure.INTERNAL_ERROR,
                                new OperationException(
                                                OperationFailure.INTERNAL_ERROR,
                                                "internal",
                                                new ErrorDetails.WorldUnavailable.OperationFailed())
                                        .failure()));
    }

    @Test
    void requiresVersionFourErrorAndReconciliationIds() {
        UUID versionOne = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        UUID versionFour = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");

        assertThrows(
                IllegalArgumentException.class,
                () -> new ErrorDetails.EditNotFound("world", versionOne));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        assertEquals(
                                OperationFailure.INTERNAL_ERROR,
                                new OperationException(
                                                OperationFailure.INTERNAL_ERROR,
                                                "internal",
                                                null,
                                                null,
                                                versionOne)
                                        .failure()));
        assertEquals(
                versionFour,
                new OperationException(
                                OperationFailure.INTERNAL_ERROR,
                                "internal",
                                null,
                                null,
                                versionFour)
                        .editId()
                        .orElseThrow());
    }

    @Test
    void requiresEditNotLatestIdsToIdentifyDifferentEdits() {
        UUID editId = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");

        assertThrows(
                IllegalArgumentException.class,
                () -> new ErrorDetails.EditNotLatest("world", editId, editId));
    }

    @Test
    void rejectsWireIntegersThatJavaScriptCannotRepresentExactly() {
        long aboveSafeInteger = 9_007_199_254_740_992L;

        assertThrows(
                IllegalArgumentException.class,
                () -> new ErrorDetails.Dimensions(aboveSafeInteger, 1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ErrorDetails.InvalidRequest.OutOfRange("field", aboveSafeInteger, 0, 1));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ErrorDetails.InvalidRequest.PaletteWeightTotal(
                                "palette", aboveSafeInteger));
    }

    @Test
    void requiresOutOfRangeValuesToActuallyBeOutsideTheAllowedRange() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ErrorDetails.InvalidRequest.OutOfRange("field", 1, 1, 3));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ErrorDetails.InvalidRequest.OutOfRange("field", 2, 1, 3));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ErrorDetails.InvalidRequest.OutOfRange("field", 3, 1, 3));
    }

    @Test
    void requiresTooManyItemFieldsToBeNonemptyAndDistinct() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ErrorDetails.InvalidRequest.TooManyItems(List.of(), 1));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ErrorDetails.InvalidRequest.TooManyItems(
                                List.of("palettes", "palettes"), 1));
    }

    @Test
    void requiresUnsupportedValuesToOfferDistinctChoices() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ErrorDetails.InvalidRequest.UnsupportedValue("strategy", List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ErrorDetails.InvalidRequest.UnsupportedValue(
                                "strategy", List.of("first", "first")));
    }

    @Test
    void requiresPaletteWeightTotalsToDescribeAnActualMismatch() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ErrorDetails.InvalidRequest.PaletteWeightTotal("palette", 100));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ErrorDetails.InvalidRequest.PaletteWeightTotal("palette", 0));
    }

    @Test
    void requiresRegionVolumeDetailsToExceedTheMaximumWithoutOverflow() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ErrorDetails.RegionTooLarge.Volume(
                                new ErrorDetails.Dimensions(2, 2, 2), 8));

        ErrorDetails.RegionTooLarge.Volume enormous =
                new ErrorDetails.RegionTooLarge.Volume(
                        new ErrorDetails.Dimensions(9_007_199_254_740_991L, 1, 1),
                        Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, enormous.maximum());
    }
}
