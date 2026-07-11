package su.onno.ui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Conditional row formatting ({@link ListSpec#rowStyle}): {@link UiViewResolver#rowStyle(Class)}
 * resolves an entity's authored row-style function — the default-profile view first, then any
 * profile-specific one — or {@code null} when no view declares one. The list-data feed evaluates
 * it per row and attaches the tone under {@code _style}.
 */
class UiViewResolverRowStyleTest {

    private static final class Styled {}
    private static final class Plain {}
    private static final class ProfileOnly {}

    private static EntityView view(Class<?> entity, String profile,
                                   Function<ActionRow, ListSpec.RowStyle> style) {
        return new EntityView() {
            @Override public Class<?> entity() { return entity; }
            @Override public String profile() { return profile; }
            @Override public void list(ListSpec spec) {
                if (style != null) {
                    spec.rowStyle(style);
                }
            }
        };
    }

    @Test
    void resolvesTheAuthoredFunction_orNullWhenNoneDeclared() {
        UiViewResolver resolver = new UiViewResolver(null, List.of(
                view(Styled.class, null, row -> row.bool("urgent") ? ListSpec.RowStyle.DANGER : null),
                view(Plain.class, null, null)));

        Function<ActionRow, ListSpec.RowStyle> fn = resolver.rowStyle(Styled.class);
        assertThat(fn).isNotNull();
        assertThat(fn.apply(new ActionRow(Map.of("urgent", true)))).isEqualTo(ListSpec.RowStyle.DANGER);
        assertThat(fn.apply(new ActionRow(Map.of("urgent", false)))).isNull();

        assertThat(resolver.rowStyle(Plain.class)).as("no rowStyle authored").isNull();
        assertThat(resolver.rowStyle(Object.class)).as("no view at all").isNull();
        assertThat(resolver.rowStyle(null)).isNull();
    }

    @Test
    void profileSpecificViewSuppliesTheFunction_whenTheDefaultViewHasNone() {
        UiViewResolver resolver = new UiViewResolver(null, List.of(
                view(ProfileOnly.class, null, null),
                view(ProfileOnly.class, "warehouse", row -> ListSpec.RowStyle.MUTED)));

        Function<ActionRow, ListSpec.RowStyle> fn = resolver.rowStyle(ProfileOnly.class);
        assertThat(fn).isNotNull();
        assertThat(fn.apply(new ActionRow(Map.of()))).isEqualTo(ListSpec.RowStyle.MUTED);
    }

    @Test
    void defaultProfileViewWins_overAProfileSpecificOne() {
        UiViewResolver resolver = new UiViewResolver(null, List.of(
                view(Styled.class, "warehouse", row -> ListSpec.RowStyle.MUTED),
                view(Styled.class, null, row -> ListSpec.RowStyle.ACCENT)));

        assertThat(resolver.rowStyle(Styled.class).apply(new ActionRow(Map.of())))
                .isEqualTo(ListSpec.RowStyle.ACCENT);
    }
}
