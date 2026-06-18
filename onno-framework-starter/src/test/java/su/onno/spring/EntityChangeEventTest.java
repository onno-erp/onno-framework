package su.onno.spring;

import su.onno.events.EntityChangedEvent;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.security.SecretCipher;
import su.onno.spring.fixtures.TestService;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the {@code repository.save}/{@code delete} write path emits {@link EntityChangedEvent}s
 * (issues #28, #29): without this, anything driven off change events only saw back-office edits, never
 * programmatic saves. The events carry the natural key (catalog code) so listeners can target a
 * resource rather than invalidating everything.
 */
class EntityChangeEventTest {

    private final MetadataRegistry registry = buildRegistry();
    private final SecretCipher cipher = new SecretCipher("test-secret-key");

    @Test
    void save_emitsCreatedThenUpdated_withNaturalKey() {
        List<EntityChangedEvent> captured = new ArrayList<>();
        OnnoAfterSaveCallback callback = new OnnoAfterSaveCallback(null, registry, cipher, captured::add);

        TestService service = new TestService();
        service.setId(UUID.randomUUID());
        service.setCode("S-1");
        service.setName("Rabies shot");
        // isNew defaults true → first save is an insert.

        callback.onAfterSave(service);
        callback.onAfterSave(service); // isNew now false → an update

        assertThat(captured).hasSize(2);
        assertThat(captured.get(0).changeType()).isEqualTo(EntityChangedEvent.CREATED);
        assertThat(captured.get(0).entityType()).isEqualTo(EntityChangedEvent.CATALOG);
        assertThat(captured.get(0).entityName()).isEqualTo("TestServices");
        assertThat(captured.get(0).naturalKey()).isEqualTo("S-1");
        assertThat(captured.get(0).id()).isEqualTo(service.getId());
        assertThat(captured.get(1).changeType()).isEqualTo(EntityChangedEvent.UPDATED);
    }

    @Test
    void delete_emitsDeleted() {
        List<EntityChangedEvent> captured = new ArrayList<>();
        OnnoBeforeDeleteCallback callback = new OnnoBeforeDeleteCallback(null, registry, captured::add);

        TestService service = new TestService();
        service.setId(UUID.randomUUID());
        service.setCode("S-9");
        service.setName("Gone");

        callback.onBeforeDelete(service, null);

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).changeType()).isEqualTo(EntityChangedEvent.DELETED);
        assertThat(captured.get(0).entityName()).isEqualTo("TestServices");
        assertThat(captured.get(0).naturalKey()).isEqualTo("S-9");
    }

    @Test
    void nullPublisher_isNoOp() {
        OnnoAfterSaveCallback callback = new OnnoAfterSaveCallback(null, registry, cipher, null);
        TestService service = new TestService();
        service.setId(UUID.randomUUID());
        service.setCode("S-2");
        service.setName("No listener");
        // Must not throw when no publisher is wired.
        callback.onAfterSave(service);
    }

    private static MetadataRegistry buildRegistry() {
        MetadataRegistry registry = new MetadataRegistry();
        registry.registerCatalog(new MetadataScanner(new DefaultNamingStrategy()).scan(TestService.class));
        return registry;
    }
}
