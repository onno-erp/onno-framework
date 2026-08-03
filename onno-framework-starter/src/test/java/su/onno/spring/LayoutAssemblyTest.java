package su.onno.spring;

import org.junit.jupiter.api.Test;
import su.onno.ui.Layout;
import su.onno.ui.LayoutSpec;
import su.onno.ui.NavStyle;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LayoutAssemblyTest {

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
