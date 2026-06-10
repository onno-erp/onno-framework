package com.onec.ui;

import java.util.List;

/**
 * A declarative related-list (inline child rows) panel for a catalog editor, backed by a
 * <em>join catalog</em> rather than an owned tabular section. It gives catalog↔catalog
 * many-to-many relationships the same inline-editing ergonomics that {@code @TabularSection}
 * gives documents, while keeping a single source of truth: the join rows themselves.
 *
 * <p>Authored on {@link EntityView#fields} via
 * {@link EntityConfigBuilder#relatedList(String, Class)}:</p>
 *
 * <pre>
 * // In ClinicView.fields(...)
 * f.relatedList("doctors", ClinicDoctor.class)
 *     .via("clinic")        // the Ref&lt;Clinic&gt; that scopes rows to this record
 *     .display("doctor")    // the Ref&lt;Doctor&gt; shown / picked per row
 *     .columns("doctor");   // optional extra join-row columns (defaults to the display ref)
 * </pre>
 *
 * <p>This is editor-only configuration — it adds no tables and changes no schema. The panel
 * reads live from the join catalog (rows where {@code via} points at the current record) and
 * writes by creating/deletion-marking join rows, reusing the existing {@code Ref} picker and
 * the join catalog's {@code @AccessControl} write roles.</p>
 *
 * @param name        the panel id (unique per entity) and the REST sub-path segment
 * @param joinCatalog the {@code @Catalog} class whose rows model the relationship
 * @param via         field name of the {@code Ref} on {@code joinCatalog} that scopes rows to
 *                    this record (the back-reference to the editing catalog)
 * @param display     field name of the {@code Ref} on {@code joinCatalog} shown and picked per
 *                    row (resolved to its description); also the add-row picker's target
 * @param columns     join-row field names to render as columns; empty means just {@code display}
 * @param label       panel heading; blank means derive one from {@code name}
 * @param hideInDetail when {@code true}, the panel renders only in the edit form, not in the
 *                    read/detail view; defaults to {@code false} so a declared panel shows in both
 */
public record RelatedList(
        String name,
        Class<?> joinCatalog,
        String via,
        String display,
        List<String> columns,
        String label,
        boolean hideInDetail) {
}
