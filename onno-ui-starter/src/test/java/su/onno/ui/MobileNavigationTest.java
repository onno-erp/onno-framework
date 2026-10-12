package su.onno.ui;

import org.junit.jupiter.api.Test;
import su.onno.ui.divkit.ShellLayoutBuilder;
import su.onno.ui.divkit.Palette;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

class MobileNavigationTest {
    @Test
    void bottomBarUsesShortLabelsAndPriorityWhileDesktopKeepsOriginalLabels() throws Exception {
        var items = List.of(
            new ShellLayoutBuilder.NavItem("Other", "onno://other", "house", "/other"),
            new ShellLayoutBuilder.NavItem("Wedding pipeline", "onno://weddings", "heart", "/weddings", "Weddings", 1),
            new ShellLayoutBuilder.NavItem("Studio overview", "onno://", "house", "/", "Home", 0));
        var sections = List.of(new ShellLayoutBuilder.NavSection("Studio", "house", items));
        var mapper = new ObjectMapper();
        String mobile = mapper.writeValueAsString(ShellLayoutBuilder.nav("", null, sections, NavStyle.BOTTOM_BAR, false, Palette.of("dark")));
        assertThat(mobile).contains("\"text\":\"Home\"", "\"text\":\"Weddings\"", "\"description\":\"Studio overview\"");
        assertThat(mobile.indexOf("\"text\":\"Home\"")).isLessThan(mobile.indexOf("\"text\":\"Weddings\""));
        assertThat(mobile).doesNotContain("\"max_lines\":2");
        String desktop = mapper.writeValueAsString(ShellLayoutBuilder.nav("", null, sections, NavStyle.SIDEBAR, false, Palette.of("dark")));
        assertThat(desktop).contains("\"text\":\"Wedding pipeline\"").doesNotContain("\"text\":\"Weddings\"");
    }
}
