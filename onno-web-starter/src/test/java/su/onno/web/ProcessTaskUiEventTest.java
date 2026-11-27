package su.onno.web;

import su.onno.service.AccessService;
import org.junit.jupiter.api.Test;
import su.onno.metadata.MetadataRegistry;
import su.onno.process.ProcessTasksChangedEvent;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessTaskUiEventTest {

    @Test
    void routesOnlyToCandidatesAndProcessAdministrators() {
        Set<String> users = Set.of("alex");
        Set<String> roles = Set.of("MANAGER");

        assertThat(EventPublisher.canReceiveProcessTasks(
                false, Set.of("VIEWER"), "alex", users, roles)).isTrue();
        assertThat(EventPublisher.canReceiveProcessTasks(
                false, Set.of("MANAGER"), "mara", users, roles)).isTrue();
        // processes.administer, not a role name, widens the audience.
        assertThat(EventPublisher.canReceiveProcessTasks(
                true, Set.of("VIEWER"), "root", users, roles)).isTrue();
        assertThat(EventPublisher.canReceiveProcessTasks(
                false, Set.of("ADMIN"), "root", users, roles)).isFalse();
        assertThat(EventPublisher.canReceiveProcessTasks(
                false, Set.of("VIEWER"), "olivia", users, roles)).isFalse();
    }

    @Test
    void springEventUsesTheTaskInvalidationSink() {
        CapturingPublisher publisher = new CapturingPublisher();
        UUID instanceId = UUID.randomUUID();

        publisher.onProcessTasksChanged(new ProcessTasksChangedEvent(
                instanceId, Set.of("alex"), Set.of("MANAGER")));

        assertThat(publisher.instanceId).isEqualTo(instanceId.toString());
        assertThat(publisher.users).containsExactly("alex");
        assertThat(publisher.roles).containsExactly("MANAGER");
    }

    private static final class CapturingPublisher extends EventPublisher {
        private String instanceId;
        private Set<String> users;
        private Set<String> roles;

        private CapturingPublisher() {
            super(new AccessService(new MetadataRegistry()));
        }

        @Override
        public void publishProcessTasksChanged(
                String instanceId, Set<String> audienceUsers, Set<String> audienceRoles) {
            this.instanceId = instanceId;
            this.users = audienceUsers;
            this.roles = audienceRoles;
        }
    }
}
