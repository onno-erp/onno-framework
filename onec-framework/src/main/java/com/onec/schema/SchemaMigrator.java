package com.onec.schema;

import com.onec.metadata.MetadataRegistry;

import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.List;

/**
 * Additive-only schema evolution: adds columns that exist in the metadata model but not in
 * the live database. Never renames, retypes or drops anything — for that, see
 * {@link SchemaUpgrader}, which subsumes this as part of its plan.
 *
 * <p>A column declared {@code required} is added nullable, backfilled with a neutral value,
 * and only then constrained {@code NOT NULL} — adding it constrained in one step would fail
 * on any table that already has rows (see {@link DdlRenderer#addColumn}).
 */
public class SchemaMigrator {

    private final MetadataRegistry registry;

    public SchemaMigrator(MetadataRegistry registry) {
        this.registry = registry;
    }

    public List<String> generateAdditiveDDL(Jdbi jdbi) {
        SchemaModel model = new SchemaModelBuilder(registry).build();
        DatabaseIntrospector.DbState db = jdbi.withHandle(DatabaseIntrospector::read);
        List<String> ddl = new ArrayList<>();
        for (TableModel table : model.tables()) {
            if (!db.hasTable(table.name())) {
                continue;
            }
            for (ColumnModel column : table.columns()) {
                if (!db.hasColumn(table.name(), column.name())) {
                    ddl.addAll(DdlRenderer.addColumn(table.name(), column));
                }
            }
        }
        return ddl;
    }

    public void executeAdditive(Jdbi jdbi) {
        List<String> ddl = generateAdditiveDDL(jdbi);
        jdbi.useHandle(handle -> {
            for (String statement : ddl) {
                handle.execute(statement);
            }
        });
    }
}
