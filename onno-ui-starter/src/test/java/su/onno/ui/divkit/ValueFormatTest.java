package su.onno.ui.divkit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ValueFormatTest {

    @Test
    void currencyRequiresAnExplicitIsoCode() {
        assertThat(ValueFormat.apply("currency", 12.50)).isNull();
        assertThat(ValueFormat.apply("currency:USD", 12.50)).isNotBlank();
        assertThat(ValueFormat.apply("currency:not-a-code", 12.50)).isNull();
    }
}
