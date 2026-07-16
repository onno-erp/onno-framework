package su.onno.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code UiMessages.DEFAULTS} and the SPA's fallback bundle
 * ({@code src/main/frontend/src/lib/messages.ts}) must carry the same key set. The server map is
 * the label source at runtime, but the frontend copy is what renders before {@code /api/config}
 * loads (and if it fails) — a key missing there falls back to the raw key id on screen.
 */
class UiMessagesFrontendSyncTest {

    private static final Path MESSAGES_TS = Path.of("src/main/frontend/src/lib/messages.ts");

    /** Top-level entries of the DEFAULT_MESSAGES literal: two-space indent, quoted key, colon. */
    private static final Pattern KEY_LINE = Pattern.compile("^ {2}\"([^\"]+)\":", Pattern.MULTILINE);

    @Test
    void frontendFallbackBundleMirrorsServerDefaults() {
        assertThat(MESSAGES_TS)
                .as("messages.ts location (test runs with the module directory as working dir)")
                .exists();

        Set<String> frontend = new TreeSet<>();
        Matcher m = KEY_LINE.matcher(readMessagesTs());
        while (m.find()) {
            frontend.add(m.group(1));
        }

        Set<String> server = new TreeSet<>(UiMessages.DEFAULTS.keySet());

        // Symmetric check with directional messages, so the failure says which side to edit.
        Set<String> missingInFrontend = new TreeSet<>(server);
        missingInFrontend.removeAll(frontend);
        Set<String> unknownInFrontend = new TreeSet<>(frontend);
        unknownInFrontend.removeAll(server);

        assertThat(missingInFrontend)
                .as("keys in UiMessages.DEFAULTS but absent from messages.ts — add them to the "
                        + "frontend fallback bundle in the same change")
                .isEmpty();
        assertThat(unknownInFrontend)
                .as("keys in messages.ts that UiMessages.DEFAULTS does not define — dead fallbacks "
                        + "(the server never resolves them); remove or add server-side")
                .isEmpty();
    }

    private static String readMessagesTs() {
        try {
            return Files.readString(MESSAGES_TS);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
