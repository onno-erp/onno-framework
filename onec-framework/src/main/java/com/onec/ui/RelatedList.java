package com.onec.ui;

import java.util.List;

/**
 * A declarative related-list (inline child rows) panel for a catalog <em>or document</em> view,
 * backed by a <em>junction</em> — a join catalog or an information register — rather than an owned
 * tabular section. It gives many-to-many relationships the same inline ergonomics that
 * {@code @TabularSection} gives documents, while keeping a single source of truth: the junction
 * rows themselves. Both sides of the relationship can mount a panel against the same junction, so
 * a {@code Booking} document and a {@code Client} catalog each show "their" rows with no mirroring.
 *
 * <p>Authored on {@link EntityView#fields} via
 * {@link EntityConfigBuilder#relatedList(String, Class)}:</p>
 *
 * <pre>
 * // In ClinicView.fields(...)
 * f.relatedList("doctors", ClinicDoctor.class)
 *     .via("clinic")        // the Ref&lt;Clinic&gt; that scopes rows to this record
 *     .display("doctor")    // the Ref&lt;Doctor&gt; shown / picked per row
 *     .columns("doctor");   // optional extra junction columns (defaults to the display ref)
 * </pre>
 *
 * <p>This is view-only configuration — it adds no tables and changes no schema. The panel reads
 * live from the junction (rows where {@code via} points at the current record). A <b>join-catalog</b>
 * junction is editable: add/remove create/deletion-mark join rows, reusing the existing {@code Ref}
 * picker and the join catalog's {@code @AccessControl} write roles. An <b>information-register</b>
 * junction (1C's idiomatic M:N store — two ref dimensions) is rendered <b>read-only</b> for now,
 * since information registers have no generic write REST surface yet.</p>
 *
 * @param name        the panel id (unique per entity) and the REST sub-path segment
 * @param joinCatalog the junction class whose rows model the relationship — a {@code @Catalog} or
 *                    an {@code @InformationRegister}
 * @param via         field name of the {@code Ref} on the junction that scopes rows to this record
 *                    (the back-reference to the owning catalog/document)
 * @param display     field name of the {@code Ref} on the junction shown and picked per row
 *                    (resolved to its description); also the add-row picker's target
 * @param columns     junction field names to render as columns; empty means just {@code display}
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
