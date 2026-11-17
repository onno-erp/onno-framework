package su.onno.web;

import su.onno.access.AccessSubject;

import java.util.List;
import java.util.UUID;

/**
 * Adds read-only operations to a catalog or document record from an extension module (for example
 * print forms). Contributions are offered to every viewer of the record, independent of write
 * access and read-only mode, so a contributor must only return actions the {@code subject} may
 * perform. Each is keyed and titled; how it is offered (icon, header button or menu) is the client's
 * decision, set per key by the entity's {@code commands} customization.
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
     * @param key   stable key identifying the action (the client's customization key)
     * @param title what the action is called
     * @param url   action URL, e.g. {@code onno://download/api/...}
     */
    record RecordAction(String key, String title, String url) {
    }
}
