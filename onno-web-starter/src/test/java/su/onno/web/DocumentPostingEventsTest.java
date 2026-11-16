package su.onno.web;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import su.onno.access.AccessSubject;
import su.onno.annotations.Document;
import su.onno.events.EntityChangedEvent;
import su.onno.lifecycle.Postable;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.DocumentObject;
import su.onno.posting.PostingContext;
import su.onno.posting.PostingEngine;
import su.onno.posting.PostingService;
import su.onno.schema.SchemaGenerator;
import su.onno.security.SecretCipher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentPostingEventsTest {

    @Document(name = "EventOrders")
    public static class EventOrder extends DocumentObject implements Postable {
        @Override
        public void handlePosting(PostingContext context) {}
    }

    @Test
    void uiPostAndUnpostPublishDocumentEventsExactlyOnce() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        Jdbi jdbi = Jdbi.create(ds);

        var registry = new MetadataRegistry();
        var scanner = new MetadataScanner(new DefaultNamingStrategy());
        var descriptor = scanner.scanDocument(EventOrder.class);
        registry.registerDocument(descriptor);

        new SchemaGenerator(registry).execute(jdbi);

        List<Object> events = new ArrayList<>();

        Jdbi observer = Jdbi.create(ds);

        var engine = new PostingEngine(
                jdbi, registry, Map.of(), null,
                event -> {
                    if (event instanceof EntityChangedEvent change
                            && EntityChangedEvent.DOCUMENT.equals(change.entityType())
                            && (EntityChangedEvent.POSTED.equals(change.changeType())
                            || EntityChangedEvent.UNPOSTED.equals(change.changeType()))) {

                        boolean postedInDatabase = observer.withHandle(handle ->
                                handle.createQuery(
                                                "SELECT _posted FROM "
                                                        + descriptor.tableName()
                                                        + " WHERE _id = :id")
                                        .bind("id", change.id())
                                        .mapTo(Boolean.class)
                                        .one()
                        );

                        assertThat(postedInDatabase)
                                .isEqualTo(EntityChangedEvent.POSTED.equals(change.changeType()));
                    }

                    events.add(event);
                }
        );

        var commands = new DocumentCommandService(
                registry,
                jdbi,
                new WebProperties(),
                null,
                new PostingService(engine),
                new DocumentQueryService(registry, jdbi),
                new AccessService(registry),
                events::add,
                new SecretCipher(null)
        );

        var subject = AccessSubject.system();
        var created = commands.create(
                descriptor, Map.of("number", "TEST-001"), subject);
        UUID id = UUID.fromString(created.get("_id").toString());

        events.clear();

        commands.post(descriptor, id, subject);

        assertThat(events)
                .filteredOn(event -> event instanceof EntityChangedEvent change
                        && EntityChangedEvent.DOCUMENT.equals(change.entityType()))
                .containsExactly(new EntityChangedEvent(
                        EntityChangedEvent.POSTED,
                        EntityChangedEvent.DOCUMENT,
                        descriptor.logicalName(),
                        id,
                        "TEST-001"
                ));
        // The register invalidation comes from PostingEngine; the UI command must not add a second one.
        assertThat(events)
                .filteredOn(event -> event instanceof EntityChangedEvent change
                        && EntityChangedEvent.REGISTER.equals(change.entityType()))
                .containsExactly(new EntityChangedEvent(
                        EntityChangedEvent.CHANGED, EntityChangedEvent.REGISTER, "*", id, null));

        events.clear();

        commands.unpost(descriptor, id, subject);

        assertThat(events)
                .filteredOn(event -> event instanceof EntityChangedEvent change
                        && EntityChangedEvent.DOCUMENT.equals(change.entityType()))
                .containsExactly(new EntityChangedEvent(
                        EntityChangedEvent.UNPOSTED,
                        EntityChangedEvent.DOCUMENT,
                        descriptor.logicalName(),
                        id,
                        "TEST-001"
                ));
    }
}