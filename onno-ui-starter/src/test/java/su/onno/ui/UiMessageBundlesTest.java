package su.onno.ui;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code onno.ui.locale} bundle loader and its layering: English is the code defaults (no file),
 * a shipped locale (ru) loads from the classpath as UTF-8, an unknown locale is an empty no-op, and
 * the three-layer resolution (defaults → bundle → explicit overrides) has explicit overrides win.
 */
class UiMessageBundlesTest {

    @Test
    void englishAndBlankLocalesLoadNoBundle() {
        assertThat(UiMessageBundles.load("en")).isEmpty();
        assertThat(UiMessageBundles.load("EN")).isEmpty();
        assertThat(UiMessageBundles.load("")).isEmpty();
        assertThat(UiMessageBundles.load(null)).isEmpty();
    }

    @Test
    void shippedRussianBundleLoadsAsUtf8() {
        Map<String, String> ru = UiMessageBundles.load("ru");

        // Non-empty, keyed by the same DEFAULTS keys, decoded as UTF-8 (Cyrillic intact).
        assertThat(ru).isNotEmpty();
        assertThat(ru).containsEntry("action.save", "Записать");
        assertThat(ru).containsEntry("confirm.delete.document.message",
                "Документ будет помечен на удаление. Отменить это здесь нельзя.");
        // Placeholders are preserved verbatim.
        assertThat(ru.get("list.count")).isEqualTo("Строк: {count}");
        // An escaped leading space survives (\ (копия)).
        assertThat(ru.get("duplicate.copySuffix")).isEqualTo(" (копия)");
        // Every ru key must be a real chrome key (no typos that would silently no-op).
        assertThat(UiMessages.DEFAULTS.keySet()).containsAll(ru.keySet());
    }

    @Test
    void unknownLocaleIsAnEmptyNoOp() {
        assertThat(UiMessageBundles.load("zz")).isEmpty();
    }

    @Test
    void resolutionLayersDefaultsThenBundleThenExplicitOverrides() {
        // Mirror UiAutoConfiguration#uiMessages: bundle first, explicit onno.ui.messages on top.
        Map<String, String> merged = new LinkedHashMap<>(UiMessageBundles.load("ru"));
        merged.put("action.save", "Сохранить"); // explicit override beats the bundle's "Записать"
        UiMessages messages = new UiMessages(merged);

        assertThat(messages.get("action.save")).isEqualTo("Сохранить");          // explicit wins
        assertThat(messages.get("action.delete")).isEqualTo("Удалить");          // from the ru bundle
        assertThat(messages.get("status.posted")).isEqualTo("Проведён");         // from the ru bundle
    }
}
