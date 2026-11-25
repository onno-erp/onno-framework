package su.onno.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Catalog {

    String name();

    /**
     * Human-facing display label, distinct from the URL-safe {@link #name()} identity.
     * Use it for localized or multi-word titles (e.g. {@code "Заказы поставщикам"}) while
     * keeping {@code name} ASCII and space-free so routes stay clean. Surfaces in the UI
     * metadata and is used for nav items, list/detail headings and tab titles. When empty,
     * falls back to {@link #name()}.
     */
    String title() default "";

    /** Stable DB table name. When empty, derived from {@link #name()}. */
    String tableName() default "";

    /**
     * Former names of this catalog (previous {@link #name()} or {@link #tableName()} values),
     * so the schema upgrader can rename the existing table — keeping its data — instead of
     * creating a fresh empty one. Each value is tried both through the naming strategy
     * ({@code "Products"} → {@code catalog_products}) and verbatim as a table name. Keep at
     * least the most recent former name until every deployment has migrated.
     */
    String[] previousNames() default {};

    int codeLength() default 9;

    /**
     * Enables folders and same-catalog parents. Requires a domain class extending
     * {@code HierarchicalCatalogObject<Self>}; flat catalogs extend {@code CatalogObject}.
     * Only hierarchical tables declare {@code _is_folder} and {@code _parent} columns.
     */
    boolean hierarchical() default false;

    boolean autoNumber() default true;

    String codePrefix() default "";

    String context() default "";

    /**
     * Keep a field-level change history for this entity (who changed what, when, from which value).
     * When {@code true} every write — through the generic REST/UI/import/MCP command path and through
     * typed repository saves — appends rows to the framework-managed {@code onno_record_history}
     * table: created/updated/deleted events with per-field old and new values (tabular-section rows
     * included; {@code secret} attributes are recorded as changed without their values). Off by
     * default, so untracked entities pay nothing. See {@code su.onno.history.RecordHistory}; the
     * global {@code onno.history.enabled} switch turns recording off everywhere.
     */
    boolean history() default false;
}
