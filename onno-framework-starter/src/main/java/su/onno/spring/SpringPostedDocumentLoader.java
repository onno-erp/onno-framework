package su.onno.spring;

import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.model.DocumentObject;
import su.onno.posting.PostedDocumentLoader;

import org.springframework.context.ApplicationContext;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.support.Repositories;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Loads complete Spring Data document aggregates, including tabular sections, for chronological
 * posting restoration.
 */
public final class SpringPostedDocumentLoader implements PostedDocumentLoader {

    private final MetadataRegistry registry;
    private final Repositories repositories;

    public SpringPostedDocumentLoader(MetadataRegistry registry, ApplicationContext context) {
        this.registry = registry;
        this.repositories = new Repositories(context);
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public List<DocumentObject> load(Collection<UUID> documentIds) {
        List<DocumentObject> loaded = new ArrayList<>();
        for (DocumentDescriptor descriptor : registry.allDocuments()) {
            Object candidate = repositories.getRepositoryFor(descriptor.javaClass()).orElse(null);
            if (!(candidate instanceof CrudRepository repository)) continue;
            for (Object entity : repository.findAllById(documentIds)) {
                if (entity instanceof DocumentObject document) {
                    loaded.add(document);
                }
            }
        }
        return loaded;
    }
}
