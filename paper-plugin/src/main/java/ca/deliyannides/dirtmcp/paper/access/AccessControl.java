package ca.deliyannides.dirtmcp.paper.access;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Web-owned account operations available to Paper's administrative command surface. */
public interface AccessControl extends AutoCloseable {
    CompletionStage<UserPage> listUsers(int page);

    CompletionStage<InvitationPage> listInvitations(int page);

    CompletionStage<CreateInvitationResult> createInvitation();

    CompletionStage<InvitationMutationResult> revokeInvitation(String id);

    CompletionStage<UserMutationResult> disableUser(String handle);

    CompletionStage<UserMutationResult> enableUser(String handle);

    CompletionStage<UserRecoveryResult> createUserRecovery(String handle);

    CompletionStage<UserMutationResult> unlinkUser(String handle);

    CompletionStage<MinecraftLinkChallenge> createMinecraftLinkChallenge(
            UUID minecraftUuid, String minecraftName);

    @Override
    void close();

    enum UserStatus {
        ACTIVE,
        DISABLED;

        public String wireName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    enum InvitationStatus {
        PENDING,
        ACCEPTED,
        REVOKED,
        EXPIRED;

        public String wireName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    record MinecraftAccount(UUID uuid, String name) {
        public MinecraftAccount {
            Objects.requireNonNull(uuid, "uuid");
            name = nonBlank(name, "name", 16);
        }
    }

    record UserSummary(
            String id,
            String handle,
            UserStatus status,
            MinecraftAccount minecraftAccount,
            Instant createdAt) {
        public UserSummary {
            id = nonBlank(id, "id", 256);
            handle = nonBlank(handle, "handle", 256);
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    record InvitationSummary(
            String id, InvitationStatus status, Instant createdAt, Instant expiresAt) {
        public InvitationSummary {
            id = nonBlank(id, "id", 256);
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    record UserPage(
            UUID callId,
            int page,
            int pageSize,
            long totalItems,
            int totalPages,
            List<UserSummary> items) {
        public UserPage {
            validatePage(callId, page, pageSize, totalItems, totalPages, items);
            items = List.copyOf(items);
        }
    }

    record InvitationPage(
            UUID callId,
            int page,
            int pageSize,
            long totalItems,
            int totalPages,
            List<InvitationSummary> items) {
        public InvitationPage {
            validatePage(callId, page, pageSize, totalItems, totalPages, items);
            items = List.copyOf(items);
        }
    }

    record CreateInvitationResult(UUID callId, InvitationSummary invitation, URI inviteUrl) {
        public CreateInvitationResult {
            Objects.requireNonNull(callId, "callId");
            Objects.requireNonNull(invitation, "invitation");
            requireSafeUrl(inviteUrl, "inviteUrl");
        }
    }

    record InvitationMutationResult(UUID callId, InvitationSummary invitation) {
        public InvitationMutationResult {
            Objects.requireNonNull(callId, "callId");
            Objects.requireNonNull(invitation, "invitation");
        }
    }

    record UserMutationResult(UUID callId, UserSummary user) {
        public UserMutationResult {
            Objects.requireNonNull(callId, "callId");
            Objects.requireNonNull(user, "user");
        }
    }

    record UserRecoveryResult(UUID callId, UserSummary user, URI recoveryUrl, Instant expiresAt) {
        public UserRecoveryResult {
            Objects.requireNonNull(callId, "callId");
            Objects.requireNonNull(user, "user");
            requireSafeUrl(recoveryUrl, "recoveryUrl");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    record MinecraftLinkChallenge(UUID callId, String code, URI linkUrl, Instant expiresAt) {
        public MinecraftLinkChallenge {
            Objects.requireNonNull(callId, "callId");
            code = nonBlank(code, "code", 128);
            requireSafeUrl(linkUrl, "linkUrl");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    private static void validatePage(
            UUID callId, int page, int pageSize, long totalItems, int totalPages, List<?> items) {
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(items, "items");
        if (page < 1) {
            throw new IllegalArgumentException("page must be positive");
        }
        if (pageSize < 1 || pageSize > 50) {
            throw new IllegalArgumentException("pageSize must be between 1 and 50");
        }
        if (totalItems < 0 || totalPages < 0) {
            throw new IllegalArgumentException("page totals must not be negative");
        }
        long expectedTotalPages =
                totalItems == 0 ? 0 : Math.addExact(Math.floorDiv(totalItems - 1, pageSize), 1);
        if (expectedTotalPages != totalPages) {
            throw new IllegalArgumentException("totalPages must match totalItems and pageSize");
        }
        if (items.size() > pageSize) {
            throw new IllegalArgumentException("items must not exceed pageSize");
        }
        long firstItem = (long) (page - 1) * pageSize;
        long itemsAvailableOnPage = Math.min(pageSize, Math.max(0, totalItems - firstItem));
        if (items.size() > itemsAvailableOnPage) {
            throw new IllegalArgumentException("items must belong to the reported page totals");
        }
    }

    private static String nonBlank(String value, String name, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(
                    name + " must be non-blank and at most " + maximumLength + " characters");
        }
        return value;
    }

    private static void requireSafeUrl(URI value, String name) {
        Objects.requireNonNull(value, name);
        String scheme = value.getScheme();
        String host = value.getHost();
        boolean localHttp =
                "http".equalsIgnoreCase(scheme)
                        && ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host));
        int port = value.getPort();
        if ((!"https".equalsIgnoreCase(scheme) && !localHttp)
                || host == null
                || value.getUserInfo() != null
                || port == 0
                || port > 65_535) {
            throw new IllegalArgumentException(name + " must be an HTTPS URL or local HTTP URL");
        }
    }
}
