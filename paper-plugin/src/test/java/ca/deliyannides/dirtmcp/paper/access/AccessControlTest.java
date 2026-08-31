package ca.deliyannides.dirtmcp.paper.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class AccessControlTest {
    private static final UUID CALL_ID = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");
    private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");

    @Test
    void snapshotsBoundedPagesAndUsesExactWireStatusNames() {
        List<AccessControl.UserSummary> source = new ArrayList<>();
        source.add(user());

        AccessControl.UserPage page = new AccessControl.UserPage(CALL_ID, 1, 20, 1, 1, source);
        source.clear();

        assertEquals(1, page.items().size());
        assertThrows(UnsupportedOperationException.class, () -> page.items().clear());
        assertEquals("active", AccessControl.UserStatus.ACTIVE.wireName());
        assertEquals("disabled", AccessControl.UserStatus.DISABLED.wireName());
        assertEquals("pending", AccessControl.InvitationStatus.PENDING.wireName());
        assertEquals("accepted", AccessControl.InvitationStatus.ACCEPTED.wireName());
        assertEquals("revoked", AccessControl.InvitationStatus.REVOKED.wireName());
        assertEquals("expired", AccessControl.InvitationStatus.EXPIRED.wireName());
    }

    @Test
    void validatesPageBoundsAndRequiredSummaryValues() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AccessControl.UserPage(CALL_ID, 0, 20, 0, 0, List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AccessControl.UserPage(CALL_ID, 1, 0, 0, 0, List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AccessControl.UserPage(CALL_ID, 1, 51, 0, 0, List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AccessControl.UserPage(CALL_ID, 1, 20, -1, 0, List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AccessControl.UserPage(CALL_ID, 1, 20, 21, 1, List.of(user())));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AccessControl.UserPage(CALL_ID, 2, 20, 1, 1, List.of(user())));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AccessControl.UserPage(CALL_ID, 2, 20, 21, 2, List.of(user(), user())));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AccessControl.UserPage(CALL_ID, 1, 1, 2, 2, List.of(user(), user())));
        assertThrows(
                NullPointerException.class,
                () -> new AccessControl.UserPage(null, 1, 20, 0, 0, List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new AccessControl.UserSummary(
                                "", "builder", AccessControl.UserStatus.ACTIVE, null, NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AccessControl.MinecraftAccount(CALL_ID, "x".repeat(17)));
    }

    @Test
    void allowsOnlyHttpsOrLocalHttpSecretUrlsWithoutUserInfoOrInvalidPorts() {
        AccessControl.InvitationSummary invitation = invitation();

        assertEquals(
                URI.create("https://dashboard.example/invite#secret"),
                new AccessControl.CreateInvitationResult(
                                CALL_ID,
                                invitation,
                                URI.create("https://dashboard.example/invite#secret"))
                        .inviteUrl());
        assertEquals(
                URI.create("http://localhost:3000/invite#secret"),
                new AccessControl.CreateInvitationResult(
                                CALL_ID,
                                invitation,
                                URI.create("http://localhost:3000/invite#secret"))
                        .inviteUrl());

        for (String unsafe :
                List.of(
                        "http://dashboard.example/invite",
                        "ftp://dashboard.example/invite",
                        "https://user:password@dashboard.example/invite",
                        "https://dashboard.example:0/invite",
                        "https://dashboard.example:99999/invite")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new AccessControl.CreateInvitationResult(
                                    CALL_ID, invitation, URI.create(unsafe)),
                    unsafe);
        }
    }

    private static AccessControl.UserSummary user() {
        return new AccessControl.UserSummary(
                "usr_1", "builder", AccessControl.UserStatus.ACTIVE, null, NOW);
    }

    private static AccessControl.InvitationSummary invitation() {
        return new AccessControl.InvitationSummary(
                "invite_1", AccessControl.InvitationStatus.PENDING, NOW, NOW.plusSeconds(600));
    }
}
