package su.onno.web.comments;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import su.onno.access.AccessSubject;
import su.onno.events.EntityChangedEvent;
import su.onno.web.CurrentUserResolver.CurrentUser;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The {@link CommentService} persistence contract: a thread is scoped to its
 * {@code (entityType, entityName, entityId)} triple, ordered oldest-first, and deletes are soft
 * (hidden from reads but still findable for authorization). The table is created by the service
 * constructor, so the only setup is an in-memory H2.
 */
class CommentServiceTest {

    private CommentService service;
    private Jdbi jdbi;
    private final CommentProperties properties = new CommentProperties();
    private final List<Object> publishedEvents = new ArrayList<>();
    private final UUID property = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final String alice = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:comments" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbi = Jdbi.create(ds);
        service = new CommentService(jdbi, publishedEvents::add, properties, null);
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
    void publishesReplyEventWhenAddingReplyDirectly() {
        Comment parent = service.add(
                "catalogs", "Properties", property, alice, "Alice", "First");
        String bob = UUID.randomUUID().toString();
        Comment reply = service.add(
                "catalogs", "Properties", property, bob, "Bob", "Second", parent.id());

        List<CommentRepliedEvent> replyEvents = publishedEvents.stream()
                .filter(CommentRepliedEvent.class::isInstance)
                .map(CommentRepliedEvent.class::cast)
                .toList();

        assertThat(replyEvents).singleElement().satisfies(event -> {
            assertThat(event.reply()).isEqualTo(reply);
            assertThat(event.parent()).isEqualTo(parent);
            assertThat(event.actor().recordId()).isEqualTo(bob);
        });
    }

    @Test
    void publishesOneEventPerReadableMentionAndDegradesForbiddenLinks() {
        var actor = new CurrentUser("alice", "Alice", alice, null, null);
        var subject = AccessSubject.user("alice", java.util.Set.of("STAFF"));
        var allowed = new MentionRef("catalogs", "People", UUID.randomUUID());
        var forbidden = new MentionRef("documents", "Orders", UUID.randomUUID());
        var resolver = mock(MentionResolver.class);
        when(resolver.canRead(subject, allowed)).thenReturn(true);
        when(resolver.resolve(List.of(allowed), subject)).thenReturn(List.of(
                new MentionResolver.ResolvedMention(
                        allowed.kind(), allowed.name(), allowed.id(), "Bob", null, "People", true)));
        service = new CommentService(jdbi, publishedEvents::add, properties, resolver);
        String mention = Mentions.token("Bob", allowed);
        String body = mention + " " + Mentions.token("Hidden", forbidden) + " " + mention;

        Comment saved = service.add("catalogs", "Properties", property, actor, body, null, subject);

        assertThat(saved.body()).isEqualTo(mention + " Hidden " + mention);
        assertThat(publishedEvents).containsExactly(
                new EntityChangedEvent(EntityChangedEvent.CREATED, "comment", "Properties", property, null),
                new EntityMentionedEvent(saved, allowed, actor));
    }

    @Test
    void enforcesConfiguredLengthBeforeWritingOrPublishing() {
        properties.setMaxLength(5);
        assertThatThrownBy(() -> service.add("catalogs", "Properties", property, alice, "Alice", "abcdef"))
                .isInstanceOf(CommentService.InvalidCommentException.class)
                .hasMessage("Comment must be at most 5 characters");
        assertThat(service.list("catalogs", "Properties", property)).isEmpty();
        assertThat(publishedEvents).isEmpty();

        Comment saved = service.add("catalogs", "Properties", property, alice, "Alice", " abcde ");
        assertThat(saved.body()).isEqualTo("abcde");
        assertThat(publishedEvents).containsExactly(
                new EntityChangedEvent(EntityChangedEvent.CREATED, "comment", "Properties", property, null));
    }

    @Test
    void eventsFollowTheOutcomeOfAnOuterJdbiTransaction() {
        Comment parent = service.add("catalogs", "Properties", property, alice, "Alice", "parent");
        publishedEvents.clear();

        jdbi.useTransaction(handle -> {
            service.add("catalogs", "Properties", property, null, "Guest", "rolled back", parent.id());
            service.toggleReaction(parent.id(), "guest", "👍");
            service.softDelete(parent.id());
            handle.rollback();
        });
        assertThat(publishedEvents).isEmpty();
        assertThat(service.list("catalogs", "Properties", property)).containsExactly(parent);
        assertThat(service.reactionsFor(List.of(parent.id()), "guest")).isEmpty();

        jdbi.useTransaction(handle -> {
            service.add("catalogs", "Properties", property, null, "Guest", "committed", parent.id());
            service.toggleReaction(parent.id(), "guest", "👍");
            service.softDelete(parent.id());
            assertThat(publishedEvents).isEmpty();
        });
        assertThat(publishedEvents).hasSize(4);
        assertThat(publishedEvents.stream().filter(CommentRepliedEvent.class::isInstance)).hasSize(1);
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
