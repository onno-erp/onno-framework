package su.onno.spring;

import org.junit.jupiter.api.Test;
import su.onno.ui.Layout;
import su.onno.ui.LayoutSpec;
import su.onno.ui.NavStyle;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LayoutAssemblyTest {

    @Test
    void composesHostShellWithReusableModuleNavigation() {
        Layout host = spec -> spec.shell().nav(NavStyle.SIDEBAR).brand("Host CRM");
        Layout crmModule = spec -> spec.section("Inbox")
                .page("/inbox", "Inbox", "inbox");

        var resolved = OnnoAutoConfiguration.buildUiLayout(List.of(host, crmModule));

        assertThat(resolved.shell().nav()).isEqualTo(NavStyle.SIDEBAR);
        assertThat(resolved.shell().branding().appName()).isEqualTo("Host CRM");
        assertThat(resolved.sections()).singleElement().satisfies(section -> {
            assertThat(section.name()).isEqualTo("Inbox");
            assertThat(section.pageRefs()).singleElement()
                    .satisfies(page -> assertThat(page.route()).isEqualTo("/inbox"));
        });
    }

    @Test
    void namedProfileCannotSilentlyDiscardShellConfiguration() {
        Layout profile = new Layout() {
            @Override public String profile() { return "sales"; }

            @Override
            public void configure(LayoutSpec spec) {
                spec.roles("SALES");
                spec.shell().nav(NavStyle.SIDEBAR);
            }
        };

        assertThatThrownBy(() -> OnnoAutoConfiguration.buildUiLayout(List.of(profile)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default Layout");
    }

    @Test
    void namedProfileCannotSilentlyDiscardIdentityConfiguration() {
        Layout profile = new Layout() {
            @Override public String profile() { return "sales"; }

            @Override
            public void configure(LayoutSpec spec) {
                spec.roles("SALES");
                spec.identity(String.class, "value");
            }
        };

        assertThatThrownBy(() -> OnnoAutoConfiguration.buildUiLayout(List.of(profile)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default Layout");
    }
}
