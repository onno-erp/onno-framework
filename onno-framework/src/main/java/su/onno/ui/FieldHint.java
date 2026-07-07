package su.onno.ui;

/**
 * UI hints for a single field, configured via {@link UiLayoutBuilder}.
 *
 * <p>All fields are nullable so callers can distinguish "not set" from
 * "explicitly set to the default value". When merged with the descriptor
 * produced by {@code MetadataScanner}, only non-null fields override the
 * scanner defaults.</p>
 */
public record FieldHint(
        Boolean visibleInList,
        Boolean visibleInForm,
        Boolean visibleInDetail,
        Integer order,
        String group,
        String width,
        String widget,
        String placeholder,
        String format,
        String hint,
        String label,
        String refSecondary
) {
    public static FieldHint empty() {
        return new FieldHint(null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
