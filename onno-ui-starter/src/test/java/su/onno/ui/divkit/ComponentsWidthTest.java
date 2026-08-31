package su.onno.ui.divkit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ComponentsWidthTest {

    @Test
    void parsesOnlyExplicitPositivePixelWidths() {
        assertThat(Components.parseWidth("240")).isEqualTo(240);
        assertThat(Components.parseWidth("240px")).isEqualTo(240);
        assertThat(Components.parseWidth(" 240px ")).isEqualTo(240);
        assertThat(Components.parseWidth("1/2")).isEqualTo(-1);
        assertThat(Components.parseWidth("half")).isEqualTo(-1);
        assertThat(Components.parseWidth("50%")).isEqualTo(-1);
        assertThat(Components.parseWidth("1px-not-really")).isEqualTo(-1);
        assertThat(Components.parseWidth("0")).isEqualTo(-1);
    }
}
