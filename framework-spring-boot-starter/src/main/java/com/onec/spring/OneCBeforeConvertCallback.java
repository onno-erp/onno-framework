package com.onec.spring;

import com.onec.lifecycle.BeforeWriteHandler;
import com.onec.model.AccumulationRecord;
import com.onec.model.CatalogObject;
import com.onec.model.DocumentObject;

import org.springframework.data.relational.core.mapping.event.BeforeConvertCallback;

import java.util.UUID;

public class OneCBeforeConvertCallback implements BeforeConvertCallback<Object> {

    @Override
    public Object onBeforeConvert(Object aggregate) {
        // Generate UUID for new entities
        if (aggregate instanceof CatalogObject catalog) {
            if (catalog.getId() == null) {
                catalog.setId(UUID.randomUUID());
            }
        } else if (aggregate instanceof DocumentObject document) {
            if (document.getId() == null) {
                document.setId(UUID.randomUUID());
            }
        } else if (aggregate instanceof AccumulationRecord record) {
            if (record.getId() == null) {
                record.setId(UUID.randomUUID());
            }
        }

        // Call BeforeWriteHandler
        if (aggregate instanceof BeforeWriteHandler handler) {
            handler.beforeWrite();
        }

        return aggregate;
    }
}
