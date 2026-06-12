package com.onec.migration;

import com.onec.metadata.MetadataRegistry;
import com.onec.schema.SqlDialect;

import org.jdbi.v3.core.Handle;

/**
 * Everything an {@link AppMigration} gets to work with: a JDBI {@link Handle} bound to the
 * transaction the migration (and its history record) runs in, the metadata registry for
 * resolving table/column names from entity metadata, and the active SQL dialect.
 */
public record MigrationContext(Handle handle, MetadataRegistry registry, SqlDialect dialect) {

    /** Convenience shortcut for {@code handle().execute(sql)}. */
    public void execute(String sql) {
        handle.execute(sql);
    }
}
