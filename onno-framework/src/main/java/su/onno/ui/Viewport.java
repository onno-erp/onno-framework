package su.onno.ui;

/**
 * The device class a client reports for itself, so the framework can serve a
 * layout and page composition tailored to it. The client picks its viewport from
 * width breakpoints and sends it as a token ({@code mobile}/{@code tablet}/
 * {@code desktop}); the resolver matches a viewport-specific {@link Layout}/
 * {@link Page} when one is authored, else falls back to the universal one.
 */
public enum Viewport {
    MOBILE,
    TABLET,
    DESKTOP;

    /** Parse a client token; unknown/blank defaults to {@link #DESKTOP}. */
    public static Viewport parse(String token) {
        if (token == null || token.isBlank()) {
            return DESKTOP;
        }
        return switch (token.trim().toLowerCase()) {
            case "mobile" -> MOBILE;
            case "tablet" -> TABLET;
            default -> DESKTOP;
        };
    }

    /** The lowercase wire token for this viewport. */
    public String token() {
        return name().toLowerCase();
    }
}
