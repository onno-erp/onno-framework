package su.onno.schema;

import java.util.Locale;

/**
 * How the framework treats schema differences at startup ({@code onno.schema.mode}).
 *
 * <ul>
 *   <li>{@link #APPLY} (default) — execute safe changes; report destructive ones unless
 *       {@code onno.schema.allow-destructive=true}.</li>
 *   <li>{@link #PLAN} — log the migration plan without touching the schema. Review it,
 *       then switch to {@code apply}.</li>
 *   <li>{@link #VALIDATE} — fail startup if the database does not match the metadata
 *       (or unapplied {@code AppMigration}s exist). For production, paired with running
 *       {@code apply} as a deploy step.</li>
 *   <li>{@link #OFF} — do nothing; schema and migrations are managed externally.</li>
 * </ul>
 */
public enum SchemaMode {

    APPLY,
    PLAN,
    VALIDATE,
    OFF;

    public static SchemaMode fromString(String value) {
        if (value == null || value.isBlank()) {
            return APPLY;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown onno.schema.mode '" + value + "'. Expected one of: apply, plan, validate, off.");
        }
    }
}
