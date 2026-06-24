package su.onno.ui.presence;

import su.onno.cluster.NoOpClusterEventBus;
import su.onno.ui.CurrentUserResolver;
import su.onno.ui.CurrentUserResolver.CurrentUser;
import su.onno.ui.UiAccessService;
import su.onno.ui.UiEventPublisher;

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
    private final PresenceController controller = new PresenceController(registry, access, currentUser);

    private final Principal principal = () -> "alice";
    private final UUID id = UUID.randomUUID();

    @Test
    void enterStampsIdentityRoutesToRegistryAndReturnsViewersAndYou() {
        access.canRead = true;
        currentUser.user = new CurrentUser("alice", "Alice Adams", "rec-1", "Employees");
        registry.viewersFixture = List.of(Map.of("userId", "rec-2", "displayName", "Babbage"));

        Map<String, Object> response = controller.ping("catalogs", "Customers", id,
                new PresenceController.PresenceRequest("enter"), principal);

        // Identity is stamped from the principal (the record id), never asserted by the client; the
        // registry stores the route kind ("catalogs"), not the singular access type.
        assertThat(registry.lastCall).isEqualTo(
                new CapturingRegistry.Call("enter", "catalogs", "Customers", id.toString(), "rec-1", "Alice Adams"));
        assertThat(response).containsEntry("you", "rec-1");
        assertThat(response.get("viewers")).isEqualTo(List.of(Map.of("userId", "rec-2", "displayName", "Babbage")));
    }

    @Test
    void fallsBackToUsernameWhenNoLinkedRecord() {
        access.canRead = true;
        currentUser.user = new CurrentUser("admin", "admin", null, null);

        Map<String, Object> response = controller.ping("catalogs", "Customers", id,
                new PresenceController.PresenceRequest("heartbeat"), principal);

        assertThat(registry.lastCall.userId()).isEqualTo("admin");
        assertThat(registry.lastCall.action()).isEqualTo("heartbeat");
        assertThat(response).containsEntry("you", "admin");
    }

    @Test
    void rejectsAnUnknownActionWith422() {
        access.canRead = true;

        assertThatThrownBy(() -> controller.ping("catalogs", "Customers", id,
                new PresenceController.PresenceRequest("bogus"), principal))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void rejectsAMissingBodyWith422() {
        access.canRead = true;

        assertThatThrownBy(() -> controller.ping("catalogs", "Customers", id, null, principal))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void rejectsAnUnknownKindWith404() {
        assertThatThrownBy(() -> controller.ping("widgets", "Customers", id,
                new PresenceController.PresenceRequest("enter"), principal))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void forbidsAViewerWithoutReadAccessWith403() {
        access.canRead = false;

        assertThatThrownBy(() -> controller.ping("documents", "Invoices", id,
                new PresenceController.PresenceRequest("enter"), principal))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void snapshotReturnsReadableRecordsAndYou() {
        access.canRead = true;
        currentUser.user = new CurrentUser("alice", "Alice Adams", "rec-1", "Employees");
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

        FakeAccess() {
            super(null);
        }

        @Override
        public boolean canRead(Principal principal, String type, String name) {
            return canRead;
        }
    }

    static final class FakeUser extends CurrentUserResolver {
        CurrentUser user = new CurrentUser("alice", "Alice Adams", "rec-1", "Employees");

        FakeUser() {
            super(null, null, null);
        }

        @Override
        public CurrentUser resolve(Principal principal) {
            return user;
        }
    }

    static final class CapturingRegistry extends PresenceRegistry {
        record Call(String action, String type, String name, String id, String userId, String displayName) {}

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
                            String userId, String displayName) {
            lastCall = new Call(action, kind, entityName, id, userId, displayName);
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
}
