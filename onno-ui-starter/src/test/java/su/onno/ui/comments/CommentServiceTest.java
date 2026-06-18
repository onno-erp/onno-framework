package su.onno.ui.comments;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@link CommentService} persistence contract: a thread is scoped to its
 * {@code (entityType, entityName, entityId)} triple, ordered oldest-first, and deletes are soft
 * (hidden from reads but still findable for authorization). The table is created by the service
 * constructor, so the only setup is an in-memory H2.
 */
class CommentServiceTest {

    private CommentService service;
    private final UUID property = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final String alice = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:comments" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        service = new CommentService(Jdbi.create(ds));
    }

    @Test
    void addsAndListsChronologicallyScopedToEntity() {
        Comment first = service.add("catalogs", "Properties", property, alice, "Alice", "First");
        service.add("catalogs", "Properties", property, null, "Guest", "Second");
        // A comment on a different record must not leak into this thread.
        service.add("catalogs", "Properties", other, alice, "Alice", "Elsewhere");
        // Same id space but a different entity name is also a different thread.
        service.add("documents", "Invoices", property, alice, "Alice", "Different kind");

        List<Comment> thread = service.list("catalogs", "Properties", property);

        assertThat(thread).extracting(Comment::body).containsExactly("First", "Second");
        assertThat(thread.get(0).id()).isEqualTo(first.id());
        assertThat(thread.get(0).authorId()).isEqualTo(alice);
        assertThat(thread.get(1).authorId()).isNull();
        assertThat(thread.get(0).createdAt()).isNotNull();
        assertThat(thread.get(0).editedAt()).isNull();
    }

    @Test
    void softDeleteHidesFromThreadButKeepsRow() {
        Comment c = service.add("catalogs", "Properties", property, alice, "Alice", "Bye");

        assertThat(service.softDelete(c.id())).isTrue();
        assertThat(service.list("catalogs", "Properties", property)).isEmpty();
        // Still findable (for the author/admin delete check) even though it's hidden from reads.
        assertThat(service.find(c.id())).isPresent();
        // A second delete is a no-op.
        assertThat(service.softDelete(c.id())).isFalse();
    }

    @Test
    void findReturnsStoredFieldsAndEmptyForUnknown() {
        Comment c = service.add("documents", "Invoices", property, alice, "Alice", "Hi there");

        Comment found = service.find(c.id()).orElseThrow();
        assertThat(found.entityType()).isEqualTo("documents");
        assertThat(found.entityName()).isEqualTo("Invoices");
        assertThat(found.entityId()).isEqualTo(property);
        assertThat(found.body()).isEqualTo("Hi there");

        assertThat(service.find(UUID.randomUUID())).isEmpty();
    }
}
