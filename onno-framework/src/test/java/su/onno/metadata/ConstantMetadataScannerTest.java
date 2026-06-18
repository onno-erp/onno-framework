package su.onno.metadata;

import su.onno.fixtures.TestCompanyName;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ConstantMetadataScannerTest {

    private MetadataScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new MetadataScanner(new DefaultNamingStrategy());
    }

    @Test
    void scanConstant_returnsCorrectDescriptor() {
        ConstantDescriptor desc = scanner.scanConstant(TestCompanyName.class);

        assertThat(desc.logicalName()).isEqualTo("CompanyName");
        assertThat(desc.javaClass()).isEqualTo(TestCompanyName.class);
        assertThat(desc.valueType()).isEqualTo(String.class);
        assertThat(desc.fieldName()).isEqualTo("value");
    }

    @Test
    void scanConstant_classWithoutAnnotation_throws() {
        assertThatThrownBy(() -> scanner.scanConstant(String.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not annotated with @Constant");
    }
}
