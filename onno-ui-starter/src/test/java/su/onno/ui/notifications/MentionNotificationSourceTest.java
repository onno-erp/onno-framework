package su.onno.ui.notifications;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.ui.CurrentUserResolver.CurrentUser;
import su.onno.ui.UiIdentityLink;
import su.onno.ui.UiLayout;
import su.onno.ui.comments.Comment;
import su.onno.ui.comments.EntityMentionedEvent;
import su.onno.ui.comments.MentionRef;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MentionNotificationSource} only turns a mention <em>of a user</em> into a notification: a mention
 * pointing at the identity catalog notifies that user, a mention of any other entity notifies nobody, and
 * a self-mention is suppressed. No DB or SSE is involved — the source resolves the identity catalog from
 * the layout + registry and calls {@link NotificationService#notify}.
 */
class MentionNotificationSourceTest {

    /** The class a login links to; its catalog is "Employees" → route name "employees". */
    private static final class IdentityEntity {}

    private CapturingService service;
    private MentionNotificationSource source;

    private final UUID targetUser = UUID.randomUUID();
    private final CurrentUser actor = new CurrentUser("ada", "Ada Lovelace", UUID.randomUUID().toString(),
            "Employees", null);

    @BeforeEach
    void setUp() {
        MetadataRegistry registry = new MetadataRegistry();
        registry.registerCatalog(new CatalogDescriptor("Employees", "Employees", "employees",
                IdentityEntity.class, 9, false, true, "E", "HR", List.of(), List.of(), List.of()));
        UiLayout layout = new UiLayout(List.of(), List.of(), List.of(),
                new UiIdentityLink(IdentityEntity.class, "email"));
        service = new CapturingService();
        source = new MentionNotificationSource(service, layout, registry);
    }

    private EntityMentionedEvent mentionOf(String kind, String name, UUID id, CurrentUser by) {
        Comment comment = new Comment(UUID.randomUUID(), "documents", "Orders", UUID.randomUUID(),
                by.recordId(), by.displayName(), "hey @[x](" + kind + "/" + name + "/" + id + ")",
                null, Instant.now(), null);
        return new EntityMentionedEvent(comment, new MentionRef(kind, name, id), by);
    }

    @Test
    void notifiesAUserMentionedByRoute() {
        source.onMention(mentionOf("catalogs", "employees", targetUser, actor));

        assertThat(service.last).isNotNull();
        assertThat(service.last.recipientId()).isEqualTo(targetUser.toString());
        assertThat(service.last.type()).isEqualTo("mention");
        assertThat(service.last.actorName()).isEqualTo("Ada Lovelace");
        // The notification opens the record whose thread the mention lives in.
        assertThat(service.last.link()).startsWith("documents/Orders/");
    }

    @Test
    void ignoresAMentionOfANonUserEntity() {
        // A customer is a catalog record too, but not the identity catalog — nobody to notify.
        source.onMention(mentionOf("catalogs", "customers", targetUser, actor));
        assertThat(service.last).isNull();
    }

    @Test
    void ignoresASelfMention() {
        UUID self = UUID.fromString(actor.recordId());
        source.onMention(mentionOf("catalogs", "employees", self, actor));
        assertThat(service.last).isNull();
    }

    /** Captures the last request instead of persisting/pushing it. */
    private static final class CapturingService extends NotificationService {
        NotificationRequest last;

        CapturingService() {
            super(null, null, null, new NotificationProperties());
        }

        @Override
        public Notification notify(NotificationRequest req) {
            this.last = req;
            return null;
        }
    }
}
