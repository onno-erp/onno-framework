package su.onno.ui;

import su.onno.access.AccessSubject;

import java.util.List;
import java.util.UUID;

/**
 * Adds read-only header actions to a catalog or document record surface from an extension module
 * (for example print forms). Contributions are offered to every viewer the record surface is served
 * to, independent of write access and read-only mode, so a contributor must only return actions the
 * {@code subject} may perform. Placement honors {@code f.action(key).primary()/inMenu()/hidden()} and
 * defaults to the overflow menu.
 */
public interface RecordActionContributor {

    /** Actions for one record; an empty list when none apply. */
    List<RecordAction> actions(RecordActionRequest request);

    /**
     * @param kind    {@code catalogs} or {@code documents}
     * @param name    the entity route segment
     * @param type    the entity class
     * @param id      the record id
     * @param subject the viewer
     */
    record RecordActionRequest(String kind, String name, Class<?> type, UUID id, AccessSubject subject) {
    }

    /**
     * @param key   placement override key ({@code f.action(key)})
     * @param icon  lucide icon name
     * @param label menu/button label
     * @param url   action URL, e.g. {@code onno://download/api/...}
     */
    record RecordAction(String key, String icon, String label, String url) {
    }
}
