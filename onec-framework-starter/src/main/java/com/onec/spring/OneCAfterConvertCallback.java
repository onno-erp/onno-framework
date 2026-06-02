package com.onec.spring;

import com.onec.model.AccumulationRecord;
import com.onec.model.CatalogObject;
import com.onec.model.DocumentObject;

import org.springframework.data.relational.core.mapping.event.AfterConvertCallback;

/**
 * Marks freshly-loaded aggregates as not-new so that a subsequent {@code repository.save(...)}
 * issues an UPDATE rather than an INSERT.
 *
 * <p>{@link CatalogObject}/{@link DocumentObject}/{@link AccumulationRecord} all implement
 * {@link org.springframework.data.domain.Persistable} with {@code isNew} defaulting to {@code true}
 * (so a brand-new instance inserts). Spring Data JDBC never flips that flag back after reading a row,
 * so without this callback an entity loaded from the database would still report {@code isNew == true}
 * and {@code save()} would attempt to re-INSERT it — causing a duplicate-key error, or a silent no-op
 * when the INSERT is ignored. Running on {@code AfterConvert} (after the row is mapped to an object,
 * on every load path) is the canonical place to reset it.
 */
public class OneCAfterConvertCallback implements AfterConvertCallback<Object> {

    @Override
    public Object onAfterConvert(Object aggregate) {
        if (aggregate instanceof CatalogObject catalog) {
            catalog.setNew(false);
        } else if (aggregate instanceof DocumentObject document) {
            document.setNew(false);
        } else if (aggregate instanceof AccumulationRecord record) {
            record.setNew(false);
        }
        return aggregate;
    }
}
