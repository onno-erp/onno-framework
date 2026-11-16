package su.onno.web;

import su.onno.access.AccessSubject;
import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Document;
import su.onno.lifecycle.Postable;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.DocumentObject;
import su.onno.posting.PostingContext;
import su.onno.posting.PostingEngine;
import su.onno.posting.PostingService;
import su.onno.schema.SchemaGenerator;
import su.onno.security.SecretCipher;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Post / unpost rights are separate from write access (#546): {@code @AccessControl(postRoles,
 * unpostRoles)} narrows who may post or unpost a document they can otherwise edit, on the shared
 * command path the REST API, MCP tools and UI actions all go through.
 */
class DocumentPostingAccessTest {

    @Document(name = "GatedInvoices", numberPrefix = "GI-")
    @AccessControl(readRoles = {"CLERK", "POSTER", "MANAGER"},
            postRoles = {"POSTER", "MANAGER"}, unpostRoles = "MANAGER")
    @SuppressWarnings("unused") // Reflective model fixture.
    public static class GatedInvoice extends DocumentObject implements Postable {
        @Attribute(length = 60) private String title;

        @Override public void handlePosting(PostingContext context) { }
    }

    /** No post/unpost lists: every writer keeps both rights (the pre-#546 behaviour). */
    @Document(name = "OpenInvoices", numberPrefix = "OI-")
    @AccessControl(readRoles = {"CLERK", "VIEWER"}, writeRoles = "CLERK")
    @SuppressWarnings("unused") // Reflective model fixture.
    public static class OpenInvoice extends DocumentObject implements Postable {
        @Attribute(length = 60) private String title;

        @Override public void handlePosting(PostingContext context) { }
    }

    private static final AccessSubject CLERK = AccessSubject.user("clerk", Set.of("CLERK"));
    private static final AccessSubject POSTER = AccessSubject.user("poster", Set.of("POSTER"));
    private static final AccessSubject MANAGER = AccessSubject.user("manager", Set.of("MANAGER"));
    private static final AccessSubject VIEWER = AccessSubject.user("viewer", Set.of("VIEWER"));
    private static final AccessSubject ADMIN = AccessSubject.user("admin", Set.of("ADMIN"));

    private Jdbi jdbi;
    private DocumentDescriptor gated;
    private DocumentDescriptor open;
    private AccessService access;
    private DocumentCommandService commands;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbi = Jdbi.create(ds);

        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        MetadataRegistry registry = new MetadataRegistry();
        gated = scanner.scanDocument(GatedInvoice.class);
        open = scanner.scanDocument(OpenInvoice.class);
        registry.registerDocument(gated);
        registry.registerDocument(open);
        new SchemaGenerator(registry).execute(jdbi);

        access = new AccessService(registry);
        PostingService posting = new PostingService(new PostingEngine(jdbi, registry, Map.of()));
        commands = new DocumentCommandService(registry, jdbi, new WebProperties(),
                new WriteLifecycleCommandServiceTest.SequentialNumbers(),
                posting, new DocumentQueryService(registry, jdbi), access, event -> { },
                new SecretCipher(null));
    }

    @Test
    void descriptorExposesPostAndUnpostRoles() {
        assertThat(gated.postRoles()).containsExactly("POSTER", "MANAGER");
        assertThat(gated.unpostRoles()).containsExactly("MANAGER");
        assertThat(open.postRoles()).isEmpty();
        assertThat(open.unpostRoles()).isEmpty();
    }

    @Test
    void writerWithoutPostRoleCannotPost() {
        UUID id = create(gated, CLERK);

        assertForbidden(() -> commands.post(gated, id, CLERK), "post");
        assertThat(posted(gated, id)).isFalse();
        // Editing is still theirs.
        assertThatCode(() -> commands.update(gated, id, Map.of("title", "edited"), CLERK))
                .doesNotThrowAnyException();
    }

    @Test
    void postRoleMayPostAndRepostButNotUnpost() {
        UUID id = create(gated, CLERK);

        commands.post(gated, id, POSTER);
        assertThat(posted(gated, id)).isTrue();
        commands.post(gated, id, POSTER); // re-post of a posted document is the same right
        assertThat(posted(gated, id)).isTrue();

        assertForbidden(() -> commands.unpost(gated, id, POSTER), "unpost");
        assertThat(posted(gated, id)).isTrue();
    }

    @Test
    void unpostRoleMayUnpost() {
        UUID id = create(gated, CLERK);
        commands.post(gated, id, MANAGER);

        commands.unpost(gated, id, MANAGER);

        assertThat(posted(gated, id)).isFalse();
    }

    @Test
    void deletingPostedDocumentNeedsUnpostRight() {
        UUID id = create(gated, CLERK);
        commands.post(gated, id, POSTER);

        assertForbidden(() -> commands.delete(gated, id, CLERK), "unpost");
        assertThat(posted(gated, id)).isTrue();
        assertThat(deleted(gated, id)).isFalse();

        commands.delete(gated, id, MANAGER);
        assertThat(deleted(gated, id)).isTrue();
    }

    @Test
    void deletingDraftNeedsOnlyWriteAccess() {
        UUID id = create(gated, CLERK);

        commands.delete(gated, id, CLERK);

        assertThat(deleted(gated, id)).isTrue();
    }

    @Test
    void adminAndSystemAlwaysPass() {
        for (AccessSubject subject : new AccessSubject[] {ADMIN, AccessSubject.system()}) {
            UUID id = create(gated, CLERK);
            commands.post(gated, id, subject);
            assertThat(posted(gated, id)).isTrue();
            commands.unpost(gated, id, subject);
            assertThat(posted(gated, id)).isFalse();
        }
    }

    @Test
    void unsetListsLeaveBothRightsWithEveryWriter() {
        UUID id = create(open, CLERK);

        commands.post(open, id, CLERK);
        assertThat(posted(open, id)).isTrue();
        commands.unpost(open, id, CLERK);
        assertThat(posted(open, id)).isFalse();

        // ...and still only with writers: a read-only role gets the ordinary write denial.
        assertThatThrownBy(() -> commands.post(open, id, VIEWER))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void canPostAndCanUnpostDriveTheUi() {
        assertThat(access.canPost(CLERK, gated)).isFalse();
        assertThat(access.canUnpost(CLERK, gated)).isFalse();
        assertThat(access.canPost(POSTER, gated)).isTrue();
        assertThat(access.canUnpost(POSTER, gated)).isFalse();
        assertThat(access.canPost(MANAGER, gated)).isTrue();
        assertThat(access.canUnpost(MANAGER, gated)).isTrue();
        assertThat(access.canPost(ADMIN, gated)).isTrue();
        assertThat(access.canUnpost(AccessSubject.system(), gated)).isTrue();
        // A post role never widens write access.
        assertThat(access.canPost(VIEWER, open)).isFalse();
        assertThat(access.canPost(CLERK, open)).isTrue();
    }

    private UUID create(DocumentDescriptor desc, AccessSubject subject) {
        return UUID.fromString(commands.create(desc, Map.of("title", "draft"), subject).get("_id").toString());
    }

    private boolean posted(DocumentDescriptor desc, UUID id) {
        return flag(desc, id, "_posted");
    }

    private boolean deleted(DocumentDescriptor desc, UUID id) {
        return flag(desc, id, "_deletion_mark");
    }

    private boolean flag(DocumentDescriptor desc, UUID id, String column) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT " + column + " FROM " + desc.tableName() + " WHERE _id = :id")
                .bind("id", id).mapTo(Boolean.class).one());
    }

    private static void assertForbidden(Runnable call, String operation) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getReason()).contains("not allowed to " + operation);
                });
    }
}
