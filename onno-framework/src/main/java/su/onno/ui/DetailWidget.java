package su.onno.ui;

import java.util.Map;

/**
 * A custom React widget embedded in a saved catalog/document record surface.
 * The client augments this static declaration with the current record context before invoking the
 * renderer registered for {@link #type()}.
 */
public record DetailWidget(
        String title,
        String type,
        int order,
        String width,
        Map<String, String> extraConfig,
        String hint
) {}
