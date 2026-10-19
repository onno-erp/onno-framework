package su.onno.schema;

import java.util.Locale;

/**
 * Which PostgreSQL trigram ({@code pg_trgm}) search indexes the framework maintains
 * ({@code onno.schema.search-indexes}).
 *
 * <ul>
 *   <li>{@link #AUTO} (default) — the system text columns ({@code _code}/{@code _description} on
 *       catalogs, {@code _number} on documents) plus every attribute declared
 *       {@code @Attribute(searchIndex = true)}.</li>
 *   <li>{@link #OFF} — none; framework-owned trigram indexes are dropped. For deployments that
 *       don't use substring search, or manage their search indexes themselves.</li>
 * </ul>
 *
 * <p>Either way, framework-owned trigram indexes that are no longer wanted are dropped, so the
 * index set converges on the declared one instead of being re-asserted on every boot.
 */
public enum SearchIndexMode {

    AUTO,
    OFF;

    public static SearchIndexMode fromString(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown onno.schema.search-indexes '" + value + "'. Expected one of: auto, off.");
        }
    }
}
