package su.onno.web;

import su.onno.access.AccessSubject;

import java.util.List;

/**
 * Adds read-only file downloads to a catalog/document list's selection toolbar from an extension
 * module (for example batch printing). The client POSTs {@code {"ids":[...]}} with the CSRF header to
 * the download's {@code url} and saves the attachment it returns. Downloads are offered to every
 * reader of the list, so the contributor must only return downloads the {@code subject} may use, and
 * the endpoint must enforce entity and record access itself. Keyed and titled only: the client
 * picks the icon.
 */
public interface SelectionDownloadContributor {

    /** Downloads for one list; an empty list when none apply. */
    List<SelectionDownload> downloads(SelectionRequest request);

    /**
     * @param kind    {@code catalogs} or {@code documents}
     * @param name    the entity route segment
     * @param type    the entity class
     * @param subject the viewer
     */
    record SelectionRequest(String kind, String name, Class<?> type, AccessSubject subject) {
    }

    /**
     * @param key   stable identifier (the client's customization key)
     * @param title what the download is called
     * @param url   the POST endpoint, e.g. {@code /api/print/documents/Orders/invoice/batch}
     */
    record SelectionDownload(String key, String title, String url) {
    }
}
