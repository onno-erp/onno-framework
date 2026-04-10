package com.onec.spring;

import com.onec.lifecycle.AfterWriteHandler;
import com.onec.model.AccumulationRecord;
import com.onec.model.CatalogObject;
import com.onec.model.DocumentObject;

import org.springframework.data.relational.core.mapping.event.AfterConvertCallback;
import org.springframework.data.relational.core.mapping.event.AfterSaveCallback;

public class OneCAfterSaveCallback implements AfterSaveCallback<Object>, AfterConvertCallback<Object> {

    @Override
    public Object onAfterSave(Object aggregate) {
        markNotNew(aggregate);

        // Call AfterWriteHandler
        if (aggregate instanceof AfterWriteHandler handler) {
            handler.afterWrite();
        }

        return aggregate;
    }

    @Override
    public Object onAfterConvert(Object aggregate) {
        markNotNew(aggregate);
        return aggregate;
    }

    private void markNotNew(Object aggregate) {
        if (aggregate instanceof CatalogObject catalog) {
            catalog.setNew(false);
        } else if (aggregate instanceof DocumentObject document) {
            document.setNew(false);
        } else if (aggregate instanceof AccumulationRecord record) {
            record.setNew(false);
        }
    }
}
