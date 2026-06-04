package com.onec.ui;

/**
 * UI hints for a single field, configured via {@link UiLayoutBuilder}.
 *
 * <p>All fields are nullable so callers can distinguish "not set" from
 * "explicitly set to the default value". When merged with the descriptor
 * produced by {@code MetadataScanner}, only non-null fields override the
 * value derived from {@code @UiHint} (or its absence).</p>
 *
 * <p>This is the configurer-side replacement for {@code @UiHint}. New code
 * should use the DSL rather than the annotation; the annotation remains as
 * a deprecated fallback for one release.</p>
 */
public record FieldHint(
        Boolean visibleInList,
        Boolean visibleInForm,
        Boolean visibleInDetail,
        Integer order,
        String group,
        String width,
        String widget,
        String placeholder
) {
    public static FieldHint empty() {
        return new FieldHint(null, null, null, null, null, null, null, null);
    }
}
