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

        // Identity is stamped from the principal (the record id), never asserted by the client.
        assertThat(registry.lastCall).isEqualTo(
                new CapturingRegistry.Call("enter", "catalog", "Customers", id.toString(), "rec-1", "Alice Adams"));
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

        CapturingRegistry() {
            super(new NoOpClusterEventBus(), new UiEventPublisher());
        }

        @Override
        public void onLocal(String action, String entityType, String entityName, String id,
                            String userId, String displayName) {
            lastCall = new Call(action, entityType, entityName, id, userId, displayName);
        }

        @Override
        public List<Map<String, String>> viewers(String entityType, String entityName, String id) {
            return viewersFixture;
        }
    }
}
