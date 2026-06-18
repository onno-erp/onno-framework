package su.onno.metadata;

import su.onno.fixtures.TestPriceRegister;
import su.onno.fixtures.TestSettingRegister;
import su.onno.model.Periodicity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class InformationRegisterMetadataScannerTest {

    private MetadataScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new MetadataScanner(new DefaultNamingStrategy());
    }

    @Test
    void scanInformationRegister_periodicRegister_returnsCorrectDescriptor() {
        InformationRegisterDescriptor desc = scanner.scanInformationRegister(TestPriceRegister.class);

        assertThat(desc.logicalName()).isEqualTo("Prices");
        assertThat(desc.tableName()).isEqualTo("inforeg_prices");
        assertThat(desc.javaClass()).isEqualTo(TestPriceRegister.class);
        assertThat(desc.periodicity()).isEqualTo(Periodicity.DAY);
    }

    @Test
    void scanInformationRegister_findsDimensions() {
        InformationRegisterDescriptor desc = scanner.scanInformationRegister(TestPriceRegister.class);

        assertThat(desc.dimensions()).hasSize(2);
        assertThat(desc.dimensions().stream().map(AttributeDescriptor::fieldName))
                .containsExactlyInAnyOrder("product", "warehouse");
    }

    @Test
    void scanInformationRegister_findsResources() {
        InformationRegisterDescriptor desc = scanner.scanInformationRegister(TestPriceRegister.class);

        assertThat(desc.resources()).hasSize(1);
        assertThat(desc.resources().get(0).fieldName()).isEqualTo("price");
    }

    @Test
    void scanInformationRegister_nonPeriodicRegister() {
        InformationRegisterDescriptor desc = scanner.scanInformationRegister(TestSettingRegister.class);

        assertThat(desc.periodicity()).isEqualTo(Periodicity.NONE);
        assertThat(desc.dimensions()).hasSize(1);
        assertThat(desc.resources()).hasSize(0);
        assertThat(desc.attributes()).hasSize(1);
    }

    @Test
    void scanInformationRegister_classWithoutAnnotation_throws() {
        assertThatThrownBy(() -> scanner.scanInformationRegister(String.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not annotated with @InformationRegister");
    }
}
