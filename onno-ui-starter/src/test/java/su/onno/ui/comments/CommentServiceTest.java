package su.onno.ui.comments;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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
        assertThat(thread.get(0).parentId()).isNull();
        assertThat(thread.get(0).createdAt()).isNotNull();
        assertThat(thread.get(0).editedAt()).isNull();
    }

    @Test
    void repliesRoundTripWithParentId() {
        Comment parent = service.add("catalogs", "Properties", property, alice, "Alice", "First");
        Comment reply = service.add("catalogs", "Properties", property, null, "Guest", "Second", parent.id());

        List<Comment> thread = service.list("catalogs", "Properties", property);

        assertThat(thread).extracting(Comment::id).containsExactly(parent.id(), reply.id());
        assertThat(thread.get(1).parentId()).isEqualTo(parent.id());
        assertThat(service.find(reply.id())).get().extracting(Comment::parentId).isEqualTo(parent.id());
    }

    @Test
    void togglesAndGroupsReactionsPerViewer() {
        Comment comment = service.add("catalogs", "Properties", property, alice, "Alice", "First");
        String bob = "principal:bob";

        assertThat(service.toggleReaction(comment.id(), "record:" + alice, "👍")).isTrue();
        assertThat(service.toggleReaction(comment.id(), bob, "👍")).isTrue();
        assertThat(service.toggleReaction(comment.id(), bob, "🎉")).isTrue();

        List<CommentService.CommentReaction> reactions = service.reactionsFor(List.of(comment.id()), bob)
                .get(comment.id());

        assertThat(reactions).extracting(CommentService.CommentReaction::emoji).containsExactly("👍", "🎉");
        assertThat(reactions.get(0).count()).isEqualTo(2);
        assertThat(reactions.get(0).mine()).isTrue();
        assertThat(reactions.get(1).count()).isEqualTo(1);
        assertThat(reactions.get(1).mine()).isTrue();

        assertThat(service.toggleReaction(comment.id(), bob, "👍")).isFalse();
        reactions = service.reactionsFor(List.of(comment.id()), bob).get(comment.id());
        assertThat(reactions).extracting(CommentService.CommentReaction::emoji).containsExactly("👍", "🎉");
        assertThat(reactions.get(0).count()).isEqualTo(1);
        assertThat(reactions.get(0).mine()).isFalse();
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
    void stampsCreatedAtAsAnInstantThatRoundTrips() {
        Instant before = Instant.now().minusSeconds(5);
        Comment added = service.add("catalogs", "Properties", property, alice, "Alice", "Hello");
        Instant after = Instant.now().plusSeconds(5);

        // The write path captures a real instant (was a zoneless LocalDateTime.now(), #177), so it
        // doesn't depend on the server JVM's zone.
        assertThat(added.createdAt()).isBetween(before, after);
        assertThat(added.editedAt()).isNull();

        // ...and it survives the plain TIMESTAMP column unchanged (compared at millisecond precision,
        // which the column always keeps).
        Comment readBack = service.find(added.id()).orElseThrow();
        assertThat(readBack.createdAt().toEpochMilli()).isEqualTo(added.createdAt().toEpochMilli());
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
