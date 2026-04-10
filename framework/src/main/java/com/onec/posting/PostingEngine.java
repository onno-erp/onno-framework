package com.onec.posting;

import com.onec.annotations.Document;
import com.onec.lifecycle.Postable;
import com.onec.metadata.DocumentDescriptor;
import com.onec.metadata.MetadataRegistry;
import com.onec.model.DocumentObject;

import org.jdbi.v3.core.Jdbi;

import java.util.Map;

public class PostingEngine {

    private final Jdbi jdbi;
    private final MetadataRegistry registry;
    private final Map<Class<?>, RegisterPersistence<?>> persistenceMap;

    public PostingEngine(Jdbi jdbi, MetadataRegistry registry,
                         Map<Class<?>, RegisterPersistence<?>> persistenceMap) {
        this.jdbi = jdbi;
        this.registry = registry;
        this.persistenceMap = persistenceMap;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public void post(DocumentObject document) {
        if (!(document instanceof Postable postable)) {
            throw new IllegalArgumentException(
                    document.getClass().getName() + " does not implement Postable");
        }

        DocumentDescriptor docDescriptor = registry.getDocumentDescriptor(document.getClass());

        PostingContext context = new PostingContext();
        postable.handlePosting(context);

        jdbi.useTransaction(handle -> {
            for (RegisterMovementCollection<?> collection : context.allMovements()) {
                RegisterPersistence persistence = persistenceMap.get(collection.getRegisterClass());
                if (persistence == null) {
                    throw new IllegalStateException(
                            "No persistence registered for " + collection.getRegisterClass().getName());
                }

                persistence.insertRecords(handle, collection.getRecords(),
                        document.getId(), document.getDate());
                persistence.updateTotals(handle, collection.getRecords());
            }

            handle.createUpdate("UPDATE " + docDescriptor.tableName() +
                            " SET _posted = TRUE WHERE _id = :id")
                    .bind("id", document.getId())
                    .execute();
        });

        document.setPosted(true);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public void unpost(DocumentObject document) {
        DocumentDescriptor docDescriptor = registry.getDocumentDescriptor(document.getClass());

        jdbi.useTransaction(handle -> {
            for (RegisterPersistence persistence : persistenceMap.values()) {
                persistence.reverseTotals(handle, document.getId());
                persistence.deactivateRecords(handle, document.getId());
            }

            handle.createUpdate("UPDATE " + docDescriptor.tableName() +
                            " SET _posted = FALSE WHERE _id = :id")
                    .bind("id", document.getId())
                    .execute();
        });

        document.setPosted(false);
    }
}
