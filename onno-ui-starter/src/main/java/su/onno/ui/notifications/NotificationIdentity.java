package su.onno.ui.notifications;

import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.ui.UiIdentityLink;
import su.onno.ui.UiLayout;
import su.onno.ui.comments.Mentions;

import java.lang.reflect.Field;

/**
 * Shared resolution of "the identity catalog" — the catalog a login links to via
 * {@code Layout.identity(...)} — for the built-in notification producers. A notification's recipient is
 * a <em>user</em>, so both producers only fire when the mentioned/assigned record belongs to this
 * catalog; its {@link CatalogDescriptor#logicalName()} and route name (the snake_case form the UI and a
 * {@link su.onno.ui.comments.MentionRef} use) are the keys they match on.
 */
final class NotificationIdentity {

    private NotificationIdentity() {
    }

    /** The identity catalog descriptor, or {@code null} when no {@code identity(...)} link is configured. */
    static CatalogDescriptor catalog(UiLayout layout, MetadataRegistry registry) {
        UiIdentityLink link = layout == null ? null : layout.identity();
        if (link == null) {
            return null;
        }
        return registry.allCatalogs().stream()
                .filter(c -> c.javaClass().equals(link.javaClass()))
                .findFirst().orElse(null);
    }

    /** The identity catalog's registered logical name (what a {@code Ref}'s {@code refTarget} carries), or null. */
    static String logicalName(UiLayout layout, MetadataRegistry registry) {
        CatalogDescriptor c = catalog(layout, registry);
        return c == null ? null : c.logicalName();
    }

    /** The identity catalog's route name (snake_case), the form a mention/route segment uses, or null. */
    static String routeName(UiLayout layout, MetadataRegistry registry) {
        String logicalName = logicalName(layout, registry);
        return logicalName == null ? null : Mentions.routeName(logicalName);
    }

    /** Find a declared field by name walking up the class hierarchy (so app-declared fields resolve), or null. */
    static Field field(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            }
        }
        return null;
    }
}
