package su.onno.ui.comments;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Regression guard for #171: a comment from an <em>unlinked</em> principal (an in-memory
 * {@code onno.auth.users} login carries no record id) has a null {@code authorId}, which must bind
 * as a typed {@code uuid} NULL so the {@code onno_comments} INSERT succeeds on PostgreSQL. JDBI
 * binds an untyped null as {@code character varying}; H2 silently coerces that into the
 * {@code _author_id uuid} column but PostgreSQL rejects it ("column \"_author_id\" is of type uuid
 * but expression is of type character varying"), so posting a comment as such a user used to 500 on
 * Postgres. Same class of bug as #163 (null {@code Ref<T>}/{@code _parent}). Must run on a real
 * PostgreSQL — H2 never reproduces it. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class CommentServicePostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private Jdbi jdbi;
    private CommentService service;

    private final UUID entityId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbi = Jdbi.create(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbi.useHandle(h -> h.execute("DROP TABLE IF EXISTS " + CommentService.TABLE));
        // The constructor runs CREATE TABLE IF NOT EXISTS, giving _author_id a real uuid column.
        service = new CommentService(jdbi);
    }

    @Test
    void commentFromUnlinkedAuthor_nullAuthorId_insertsOnPostgres() {
        // Before #171 this threw PSQLException (uuid vs character varying); the call itself is the guard.
        assertThatCode(() -> service.add("documents", "Invoices", entityId, null, "admin", "test"))
                .doesNotThrowAnyException();

        Comment stored = service.list("documents", "Invoices", entityId).get(0);
        assertThat(stored.authorId()).isNull();
        assertThat(stored.authorName()).isEqualTo("admin");
        assertThat(stored.body()).isEqualTo("test");

        Map<String, Object> row = rawRow(stored.id());
        assertThat(row.get("_author_id")).isNull();
    }

    @Test
    void commentFromLinkedAuthor_uuidAuthorId_stillPersists() {
        String authorId = UUID.randomUUID().toString();

        Comment stored = service.add("catalogs", "Properties", entityId, authorId, "Alice", "Hi");

        Map<String, Object> row = rawRow(stored.id());
        assertThat(row.get("_author_id")).isEqualTo(UUID.fromString(authorId));
        assertThat(service.list("catalogs", "Properties", entityId))
                .extracting(Comment::authorId)
                .containsExactly(authorId);
    }

    private Map<String, Object> rawRow(UUID id) {
        return jdbi.withHandle(h -> h.createQuery("SELECT * FROM " + CommentService.TABLE + " WHERE _id = :id")
                .bind("id", id)
                .mapToMap()
                .one());
    }
}
