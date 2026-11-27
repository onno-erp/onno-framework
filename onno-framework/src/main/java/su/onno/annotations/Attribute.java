package su.onno.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A stored field of a catalog, document or tabular-section row: its column ({@link #name},
 * {@link #length}, {@link #precision}, {@link #scale}, {@link #previousNames}), its presentation
 * ({@link #displayName}) and storage behaviour ({@link #secret}, {@link #searchIndex}).
 *
 * <p>Single-field validation is declared with the standard Jakarta Bean Validation annotations
 * ({@code @NotNull}, {@code @NotBlank}, {@code @Size}, {@code @Min}/{@code @Max}, {@code @Pattern},
 * {@code @Email}, {@code @Past}, custom constraints, ...): onno reads them into the model metadata
 * and enforces them on every write when a provider is present. The validation flags on this
 * annotation ({@link #required}, {@link #min}, {@link #max}, {@link #minLength}, {@link #pattern},
 * {@link #email}) are deprecated duplicates of those; declaring a rule both ways with different
 * values fails startup. Rules that span several fields belong in
 * {@link su.onno.rules.HasBusinessRules}.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Attribute {

    String name() default "";

    String displayName() default "";

    /**
     * Former names of this attribute, so the schema upgrader can rename the existing column
     * (keeping its data) instead of adding a fresh empty one. List the old field/attribute
     * name as it was written in Java (e.g. {@code "phone"} after renaming the field to
     * {@code phoneNumber}); it is mapped to a column name through the same naming strategy.
     * Keep at least the most recent former name until every deployment has migrated.
     */
    String[] previousNames() default {};

    /** Column length of a String attribute (the storage limit; validate a shorter one with {@code @Size(max)}). */
    int length() default 255;

    /**
     * The attribute must have a value (a String must not be blank).
     *
     * @deprecated use {@code @jakarta.validation.constraints.NotNull} or {@code @NotBlank} (Strings).
     */
    @Deprecated(since = "5.0")
    boolean required() default false;

    int precision() default 15;

    int scale() default 2;

    // ----- deprecated validation flags: use the Jakarta Bean Validation annotations instead -----

    /**
     * Minimum allowed value for a numeric attribute. {@code NaN} (default) means no bound.
     *
     * @deprecated use {@code @jakarta.validation.constraints.Min} or {@code @DecimalMin}.
     */
    @Deprecated(since = "5.0")
    double min() default Double.NaN;

    /**
     * Maximum allowed value for a numeric attribute. {@code NaN} (default) means no bound.
     *
     * @deprecated use {@code @jakarta.validation.constraints.Max} or {@code @DecimalMax}.
     */
    @Deprecated(since = "5.0")
    double max() default Double.NaN;

    /**
     * Minimum length for a String attribute. {@code 0} (default) means no minimum.
     *
     * @deprecated use {@code @jakarta.validation.constraints.Size(min = ...)}.
     */
    @Deprecated(since = "5.0")
    int minLength() default 0;

    /**
     * A regex the String value must fully match. Empty (default) means no pattern.
     *
     * @deprecated use {@code @jakarta.validation.constraints.Pattern(regexp = ...)}.
     */
    @Deprecated(since = "5.0")
    String pattern() default "";

    /**
     * The String value must be a valid email address.
     *
     * @deprecated use {@code @jakarta.validation.constraints.Email}.
     */
    @Deprecated(since = "5.0")
    boolean email() default false;

    /**
     * Marks a String attribute as sensitive (a credential, API key, password, …). A secret
     * attribute is encrypted at rest, never returned in the clear by the generic API (read
     * responses carry a "set / not set" sentinel instead of the value), and rendered as a
     * write-only password control in the UI. Only meaningful for {@code String} attributes;
     * requires {@code onno.security.secret-key} to be configured.
     */
    boolean secret() default false;

    /**
     * Opt this String attribute into a PostgreSQL trigram ({@code pg_trgm} GIN) index, so the
     * list/ref-picker substring search ({@code q=}) on it is an index lookup instead of a scan.
     * Off by default: a trigram index costs disk and slows every insert/update in proportion to
     * the column's text volume, so index only the attributes users actually search by on large
     * tables. The system columns ({@code code}/{@code description}, {@code number}) are indexed
     * regardless (subject to {@code onno.schema.search-indexes}). Search itself covers every
     * String attribute either way; this only decides whether it is index-backed. Only valid on
     * non-secret {@code String} attributes; ignored on databases other than PostgreSQL.
     */
    boolean searchIndex() default false;
}
