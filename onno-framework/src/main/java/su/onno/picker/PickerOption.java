package su.onno.picker;

/**
 * The contextual state of one picker candidate, returned by {@link PickerOptions}.
 *
 * @param badge    short status label shown beside the option
 * @param tone     semantic badge colour; ignored when {@code color} is set
 * @param color    optional explicit CSS colour (normally a hex value)
 * @param disabled whether the option can be selected
 * @param reason   optional explanation shown below a disabled option
 * @param hidden   whether the option is filtered out of this contextual result page
 */
public record PickerOption(
        String badge,
        PickerTone tone,
        String color,
        boolean disabled,
        String reason,
        boolean hidden
) {
    public PickerOption {
        badge = badge == null ? "" : badge;
        tone = tone == null ? PickerTone.NEUTRAL : tone;
        color = color == null ? "" : color;
        reason = reason == null ? "" : reason;
    }

    public static PickerOption badge(String label, PickerTone tone) {
        return new PickerOption(label, tone, "", false, "", false);
    }

    public static PickerOption disabled(String label, PickerTone tone, String reason) {
        return new PickerOption(label, tone, "", true, reason, false);
    }

    /** Filter the option out of the current search result. */
    public static PickerOption filteredOut() {
        return new PickerOption("", PickerTone.NEUTRAL, "", false, "", true);
    }
}
