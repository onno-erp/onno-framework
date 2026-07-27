package su.onno.posting;

import su.onno.model.DocumentObject;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Loads persisted document aggregates for chronological posting restoration.
 *
 * <p>The framework starter supplies an implementation backed by Spring Data repositories. Core-only
 * embedding can provide its own loader through the extended {@link PostingEngine} constructor.
 */
@FunctionalInterface
public interface PostedDocumentLoader {

    List<DocumentObject> load(Collection<UUID> documentIds);
}
