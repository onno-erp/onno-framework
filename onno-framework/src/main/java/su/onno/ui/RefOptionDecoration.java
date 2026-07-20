package su.onno.ui;

/**
 * Context-dependent presentation and selection state for one reference-picker option.
 *
 * @param badge short status label shown beside the option
 * @param tone semantic badge colour; ignored when {@code color} is set
 * @param color optional explicit CSS colour (normally a hex value)
 * @param disabled whether the option can be selected
 * @param reason optional explanation shown below a disabled option
 * @param hidden whether the option is filtered out of this contextual result page
 */
public record RefOptionDecoration(
        String badge,
        RefOptionTone tone,
        String color,
        boolean disabled,
        String reason,
        boolean hidden
) {
    public RefOptionDecoration {
        badge = badge == null ? "" : badge;
        tone = tone == null ? RefOptionTone.NEUTRAL : tone;
        color = color == null ? "" : color;
        reason = reason == null ? "" : reason;
    }

    public static RefOptionDecoration badge(String label, RefOptionTone tone) {
        return new RefOptionDecoration(label, tone, "", false, "", false);
    }

    public static RefOptionDecoration disabled(String label, RefOptionTone tone, String reason) {
        return new RefOptionDecoration(label, tone, "", true, reason, false);
    }

    /** Filter an option from the current search result using live form/row context. */
    public static RefOptionDecoration filteredOut() {
        return new RefOptionDecoration("", RefOptionTone.NEUTRAL, "", false, "", true);
    }
}
