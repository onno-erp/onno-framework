package su.onno.ui;

import su.onno.fixtures.TestProduct;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UiProfileResolverTest {

    private final UiProfileResolver resolver = new UiProfileResolver();

    private UiLayout layout() {
        UiLayoutBuilder b = new UiLayoutBuilder();
        b.section("Home").catalog(TestProduct.class);
        b.profile("cleaning").roles("CLEANER").priority(10).title("Cleaning")
                .section("Tasks").document(TestProduct.class);
        b.profile("manager").roles("MANAGER").priority(20).title("Manager")
                .section("Overview").catalog(TestProduct.class);
        return new UiLayout(b.build(), b.buildWidgets(), b.buildProfiles());
    }

    @Test
    void matchingRole_resolvesToNamedProfile() {
        UiProfileResolver.Resolution r = resolver.resolve(layout(), Set.of("CLEANER"));
        assertThat(r.profile().id()).isEqualTo("cleaning");
        assertThat(r.profile().title()).isEqualTo("Cleaning");
    }

    @Test
    void noMatchingRole_fallsBackToDefault() {
        UiProfileResolver.Resolution r = resolver.resolve(layout(), Set.of("GUEST"));
        assertThat(r.profile().id()).isEqualTo("default");
    }

    @Test
    void multipleMatches_highestPriorityWins() {
        UiProfileResolver.Resolution r = resolver.resolve(layout(), Set.of("CLEANER", "MANAGER"));
        assertThat(r.profile().id()).isEqualTo("manager");
    }

    @Test
    void switchable_isLimitedToMatchedProfiles_notDefault() {
        var switchable = resolver.switchable(layout(), Set.of("CLEANER"));
        assertThat(switchable).extracting(UiLayout.Profile::id)
                .containsExactly("cleaning");
    }

    @Test
    void switchable_includesEveryMatchedProfile() {
        var switchable = resolver.switchable(layout(), Set.of("CLEANER", "MANAGER"));
        assertThat(switchable).extracting(UiLayout.Profile::id)
                .containsExactlyInAnyOrder("cleaning", "manager");
    }

    @Test
    void switchable_fallsBackToDefault_whenNoProfileMatches() {
        var switchable = resolver.switchable(layout(), Set.of("GUEST"));
        assertThat(switchable).extracting(UiLayout.Profile::id)
                .containsExactly("default");
    }

    @Test
    void byId_returnsRequestedProfile_orDefaultForUnknown() {
        UiLayout layout = layout();
        assertThat(resolver.byId(layout, "manager").id()).isEqualTo("manager");
        assertThat(resolver.byId(layout, null).id()).isEqualTo("default");
        assertThat(resolver.byId(layout, "nope").id()).isEqualTo("default");
    }

    @Test
    void roleMatchingIsCaseAndPrefixInsensitive() {
        UiLayoutBuilder b = new UiLayoutBuilder();
        b.section("Home").catalog(TestProduct.class);
        b.profile("cleaning").roles("ROLE_cleaner").section("Tasks").document(TestProduct.class);
        UiLayout layout = new UiLayout(b.build(), b.buildWidgets(), b.buildProfiles());

        assertThat(resolver.resolve(layout, Set.of("CLEANER")).profile().id()).isEqualTo("cleaning");
    }
}
