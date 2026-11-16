package su.onno.posting;

import su.onno.annotations.Document;
import su.onno.events.EntityChangedEvent;
import su.onno.lifecycle.AfterPostHandler;
import su.onno.lifecycle.Postable;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.DocumentObject;
import su.onno.schema.SchemaGenerator;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Callbacks that run after the posting transaction commits: register views are invalidated for every
 * entry point, and a failing AfterPostHandler or event listener cannot turn a committed post into a
 * reported failure or skip the remaining callbacks.
 */
class PostCommitCallbacksTest {

    @Document(name = "CallbackInvoices", numberPrefix = "CI-")
    public static class CallbackInvoice extends DocumentObject implements Postable, AfterPostHandler {
        boolean failAfterPost;
        int afterPostCalls;

        @Override
        public void handlePosting(PostingContext context) {
            // no movements: the post-commit callbacks are under test
        }

        @Override
        public void afterPost() {
            afterPostCalls++;
            if (failAfterPost) {
                throw new IllegalStateException("notification service down");
            }
        }
    }

    private Jdbi jdbi;
    private MetadataRegistry registry;
    private DocumentDescriptor descriptor;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbi = Jdbi.create(ds);
        registry = new MetadataRegistry();
        descriptor = new MetadataScanner(new DefaultNamingStrategy()).scanDocument(CallbackInvoice.class);
        registry.registerDocument(descriptor);
        new SchemaGenerator(registry).execute(jdbi);
    }

    @Test
    void postAndUnpostInvalidateRegisterViewsFromCore() {
        List<Object> events = new ArrayList<>();
        PostingEngine engine = new PostingEngine(jdbi, registry, Map.of(), null, events::add);
        CallbackInvoice invoice = storedInvoice();

        engine.post(invoice);
        engine.unpost(invoice);

        EntityChangedEvent registersChanged = new EntityChangedEvent(
                EntityChangedEvent.CHANGED, EntityChangedEvent.REGISTER, "*", invoice.getId(), null);
        assertThat(events)
                .filteredOn(event -> event instanceof EntityChangedEvent change
                        && EntityChangedEvent.REGISTER.equals(change.entityType()))
                .containsExactly(registersChanged, registersChanged);
    }

    @Test
    void failingAfterPostDoesNotFailTheCommittedPost() {
        List<Object> events = new ArrayList<>();
        PostingEngine engine = new PostingEngine(jdbi, registry, Map.of(), null, events::add);
        CallbackInvoice invoice = storedInvoice();
        invoice.failAfterPost = true;

        engine.post(invoice);

        assertThat(invoice.afterPostCalls).isEqualTo(1);
        assertThat(invoice.isPosted()).isTrue();
        assertThat(storedPosted(invoice)).isTrue();
        assertThat(events).anyMatch(event -> event instanceof DocumentPostedEvent posted
                && posted.document() == invoice);
    }

    @Test
    void failingListenerDoesNotFailTheCommittedPostOrSkipLaterCallbacks() {
        List<Object> delivered = new ArrayList<>();
        PostingEngine engine = new PostingEngine(jdbi, registry, Map.of(), null, event -> {
            if (event instanceof EntityChangedEvent change
                    && EntityChangedEvent.DOCUMENT.equals(change.entityType())) {
                throw new IllegalStateException("live-update relay down");
            }
            delivered.add(event);
        });
        CallbackInvoice invoice = storedInvoice();

        engine.post(invoice);
        engine.unpost(invoice);

        assertThat(storedPosted(invoice)).isFalse();
        assertThat(invoice.afterPostCalls).isEqualTo(1);
        assertThat(delivered).hasAtLeastOneElementOfType(DocumentPostedEvent.class);
        assertThat(delivered).hasAtLeastOneElementOfType(DocumentUnpostedEvent.class);
    }

    private CallbackInvoice storedInvoice() {
        CallbackInvoice invoice = new CallbackInvoice();
        invoice.setId(UUID.randomUUID());
        invoice.setNumber("CI-001");
        invoice.setDate(LocalDateTime.of(2026, 3, 15, 10, 0));
        jdbi.useHandle(h -> h.createUpdate(
                        "INSERT INTO " + descriptor.tableName()
                                + " (_id, _number, _date, _posted, _deletion_mark) "
                                + "VALUES (:id, :number, :date, FALSE, FALSE)")
                .bind("id", invoice.getId())
                .bind("number", invoice.getNumber())
                .bind("date", invoice.getDate())
                .execute());
        return invoice;
    }

    private boolean storedPosted(CallbackInvoice invoice) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT _posted FROM " + descriptor.tableName() + " WHERE _id = :id")
                .bind("id", invoice.getId())
                .mapTo(Boolean.class)
                .one());
    }
}
