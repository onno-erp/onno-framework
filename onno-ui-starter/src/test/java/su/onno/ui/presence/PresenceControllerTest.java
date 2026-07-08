package su.onno.ui.presence;

import su.onno.cluster.NoOpClusterEventBus;
import su.onno.ui.CurrentUserResolver;
import su.onno.ui.CurrentUserResolver.CurrentUser;
import su.onno.ui.UiAccessService;
import su.onno.ui.UiEventPublisher;
import su.onno.ui.comments.CommentAuthorAvatars;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PresenceControllerTest {

    private final CapturingRegistry registry = new CapturingRegistry();
    private final FakeAccess access = new FakeAccess();
    private final FakeUser currentUser = new FakeUser();
    private final FakeAvatars avatars = new FakeAvatars();
    private final PresenceController controller = new PresenceController(registry, access, currentUser, avatars);

    private final Principal principal = () -> "alice";
    private final UUID id = UUID.randomUUID();

    @Test
    void enterStampsIdentityRoutesToRegistryAndReturnsViewersAndYou() {
        access.canRead = true;
        currentUser.user = new CurrentUser("alice", "Alice Adams", "rec-1", "Employees", null);
        registry.viewersFixture = List.of(Map.of("userId", "rec-2", "displayName", "Babbage"));

        Map<String, Object> response = controller.ping(
                new PresenceController.PresenceRequest("/catalogs/Customers/" + id, "enter"), principal);

        // Identity is stamped from the principal (the record id), never asserted by the client; the
        // registry stores the route kind ("catalogs"), not the singular access type.
        assertThat(registry.lastCall).isEqualTo(
                new CapturingRegistry.Call("enter", "catalogs", "Customers", id.toString(), "rec-1", "Alice Adams",
                        "pic/rec-1"));
        assertThat(response).containsEntry("you", "rec-1");
        assertThat(response.get("viewers")).isEqualTo(List.of(Map.of("userId", "rec-2", "displayName", "Babbage")));
    }

    @Test
    void fallsBackToUsernameWhenNoLinkedRecord() {
        access.canRead = true;
        currentUser.user = new CurrentUser("admin", "admin", null, null, null);

        Map<String, Object> response = controller.ping(
                new PresenceController.PresenceRequest("/catalogs/Customers/" + id, "heartbeat"), principal);

        assertThat(registry.lastCall.userId()).isEqualTo("admin");
        assertThat(registry.lastCall.action()).isEqualTo("heartbeat");
        assertThat(response).containsEntry("you", "admin");
    }

    @Test
    void rejectsAnUnknownActionWith422() {
        access.canRead = true;

        assertThatThrownBy(() -> controller.ping(
                new PresenceController.PresenceRequest("/catalogs/Customers/" + id, "bogus"), principal))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void rejectsAMissingBodyWith422() {
        access.canRead = true;

        assertThatThrownBy(() -> controller.ping(null, principal))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void entityListRouteIsTrackedAndKeyedByItsPath() {
        access.canRead = true;
        currentUser.user = new CurrentUser("alice", "Alice Adams", "rec-1", "Employees", null);

        controller.ping(new PresenceController.PresenceRequest("/catalogs/Materials", "enter"), principal);

        // A list page keys by its path so it aggregates onto the catalog's nav alongside record viewers.
        assertThat(registry.lastCall.type()).isEqualTo("catalogs");
        assertThat(registry.lastCall.name()).isEqualTo("Materials");
        assertThat(registry.lastCall.id()).isEqualTo("/catalogs/Materials");
    }

    @Test
    void pageRouteIsTrackedForAnySignedInUserWithoutEntityAccess() {
        access.canRead = false; // not an entity route, so the read gate never applies
        currentUser.user = new CurrentUser("alice", "Alice Adams", "rec-1", "Employees", null);

        controller.ping(new PresenceController.PresenceRequest("/dashboard", "enter"), principal);

        // Non-entity routes register as a "page" keyed by the normalized path.
        assertThat(registry.lastCall.type()).isEqualTo("page");
        assertThat(registry.lastCall.name()).isEqualTo("/dashboard");
        assertThat(registry.lastCall.id()).isEqualTo("/dashboard");
    }

    @Test
    void decodesPercentEncodedEntityNameSoTheReadGateMatches() {
        access.canRead = true;
        currentUser.user = new CurrentUser("alice", "Alice Adams", "rec-1", "Employees", null);
        // The SPA posts the browser's percent-encoded route path; a non-ASCII entity name arrives
        // encoded. Presence must decode it before the read gate + registry, or canRead never matches
        // and every page 403s with "…: %D1%81…" (issue #245).
        String encoded = java.net.URLEncoder.encode("Спектакли", java.nio.charset.StandardCharsets.UTF_8);

        controller.ping(new PresenceController.PresenceRequest("/catalogs/" + encoded + "/" + id, "enter"), principal);

        assertThat(access.lastName).isEqualTo("Спектакли");
        assertThat(registry.lastCall.name()).isEqualTo("Спектакли");
    }

    @Test
    void forbidsAViewerWithoutReadAccessWith403() {
        access.canRead = false;

        assertThatThrownBy(() -> controller.ping(
                new PresenceController.PresenceRequest("/documents/Invoices/" + id, "enter"), principal))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void snapshotReturnsReadableRecordsAndYou() {
        access.canRead = true;
        currentUser.user = new CurrentUser("alice", "Alice Adams", "rec-1", "Employees", null);
        Map<String, Object> rec = new HashMap<>();
        rec.put("kind", "catalogs");
        rec.put("name", "Properties");
        rec.put("id", "p1");
        rec.put("viewers", List.of(Map.of("userId", "rec-2", "displayName", "Babbage")));
        registry.allViewersFixture = List.of(rec);

        Map<String, Object> response = controller.snapshot(principal);

        assertThat(response).containsEntry("you", "rec-1");
        assertThat(response.get("records")).isEqualTo(List.of(rec));
    }

    @Test
    void snapshotOmitsRecordsInEntitiesTheCallerCannotRead() {
        access.canRead = false;
        Map<String, Object> rec = new HashMap<>();
        rec.put("kind", "catalogs");
        rec.put("name", "Salaries");
        rec.put("id", "s1");
        rec.put("viewers", List.of(Map.of("userId", "x", "displayName", "X")));
        registry.allViewersFixture = List.of(rec);

        Map<String, Object> response = controller.snapshot(principal);

        assertThat((List<?>) response.get("records")).isEmpty();
    }

    static final class FakeAccess extends UiAccessService {
        boolean canRead = true;
        String lastName; // the entity name the gate was asked about (to assert decoding)

        FakeAccess() {
            super(null);
        }

        @Override
        public boolean canRead(Principal principal, String type, String name) {
            lastName = name;
            return canRead;
        }
    }

    static final class FakeUser extends CurrentUserResolver {
        CurrentUser user = new CurrentUser("alice", "Alice Adams", "rec-1", "Employees", null);

        FakeUser() {
            super(null, null, null, null);
        }

        @Override
        public CurrentUser resolve(Principal principal) {
            return user;
        }
    }

    static final class CapturingRegistry extends PresenceRegistry {
        record Call(String action, String type, String name, String id, String userId, String displayName,
                    String avatarUrl) {}

        Call lastCall;
        List<Map<String, String>> viewersFixture = List.of();
        List<Map<String, Object>> allViewersFixture = List.of();

        CapturingRegistry() {
            // The publisher is never exercised here (onLocal is overridden to just record the call,
            // bypassing the fan-out), so a null-registry access service is fine.
            super(new NoOpClusterEventBus(), new UiEventPublisher(new UiAccessService(null)));
        }

        @Override
        public void onLocal(String action, String kind, String entityName, String id,
                            String userId, String displayName, String avatarUrl) {
            lastCall = new Call(action, kind, entityName, id, userId, displayName, avatarUrl);
        }

        @Override
        public List<Map<String, String>> viewers(String kind, String entityName, String id) {
            return viewersFixture;
        }

        @Override
        public List<Map<String, Object>> allViewers() {
            return allViewersFixture;
        }
    }

    static final class FakeAvatars extends CommentAuthorAvatars {
        FakeAvatars() {
            // resolveSource() returns null for a null layout, so this is safe; avatarFor is overridden.
            super(null, null, null, null);
        }

        @Override
        public String avatarFor(String authorId) {
            return authorId == null ? null : "pic/" + authorId;
        }
    }
}
