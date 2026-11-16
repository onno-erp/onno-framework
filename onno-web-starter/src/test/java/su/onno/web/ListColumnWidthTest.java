package su.onno.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * List column sizing is separate from form layout (#275): only an explicit pixel width hint sizes a
 * list column; form-layout hints and everything else size automatically (blank).
 */
class ListColumnWidthTest {

    @Test
    void pixelHintsSizeTheColumn() {
        assertThat(DefaultListViews.listWidth("105")).isEqualTo("105");
        assertThat(DefaultListViews.listWidth("75px")).isEqualTo("75");
        assertThat(DefaultListViews.listWidth(" 240 PX ")).isEqualTo("240");
    }

    @Test
    void formLayoutHintsAndJunkSizeAutomatically() {
        assertThat(DefaultListViews.listWidth("half")).isEmpty();
        assertThat(DefaultListViews.listWidth("1/2")).isEmpty();
        assertThat(DefaultListViews.listWidth("50%")).isEmpty();
        assertThat(DefaultListViews.listWidth("0")).isEmpty();
        assertThat(DefaultListViews.listWidth("99999999999")).isEmpty();
        assertThat(DefaultListViews.listWidth(null)).isEmpty();
    }
}
