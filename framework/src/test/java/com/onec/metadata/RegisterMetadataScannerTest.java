package com.onec.metadata;

import com.onec.fixtures.NotARegister;
import com.onec.fixtures.TestStockRegister;
import com.onec.fixtures.TestSalesRegister;
import com.onec.model.AccumulationType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class RegisterMetadataScannerTest {

    private MetadataScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new MetadataScanner(new DefaultNamingStrategy());
    }

    @Test
    void scanRegister_balanceRegister_returnsCorrectDescriptor() {
        AccumulationRegisterDescriptor desc = scanner.scanRegister(TestStockRegister.class);

        assertThat(desc.logicalName()).isEqualTo("TestStock");
        assertThat(desc.tableName()).isEqualTo("_register_TestStock");
        assertThat(desc.totalsTableName()).isEqualTo("_register_TestStock_totals");
        assertThat(desc.javaClass()).isEqualTo(TestStockRegister.class);
        assertThat(desc.accumulationType()).isEqualTo(AccumulationType.BALANCE);
    }

    @Test
    void scanRegister_findsDimensions() {
        AccumulationRegisterDescriptor desc = scanner.scanRegister(TestStockRegister.class);

        assertThat(desc.dimensions()).hasSize(2);
        assertThat(desc.dimensions().stream().map(AttributeDescriptor::fieldName))
                .containsExactlyInAnyOrder("product", "warehouse");
    }

    @Test
    void scanRegister_findsResources() {
        AccumulationRegisterDescriptor desc = scanner.scanRegister(TestStockRegister.class);

        assertThat(desc.resources()).hasSize(1);
        assertThat(desc.resources().get(0).fieldName()).isEqualTo("quantity");
        assertThat(desc.resources().get(0).precision()).isEqualTo(15);
        assertThat(desc.resources().get(0).scale()).isEqualTo(2);
    }

    @Test
    void scanRegister_turnoverRegister() {
        AccumulationRegisterDescriptor desc = scanner.scanRegister(TestSalesRegister.class);

        assertThat(desc.accumulationType()).isEqualTo(AccumulationType.TURNOVER);
        assertThat(desc.dimensions()).hasSize(1);
        assertThat(desc.resources()).hasSize(2);
    }

    @Test
    void scanRegister_classWithoutAnnotation_throws() {
        assertThatThrownBy(() -> scanner.scanRegister(String.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not annotated with @AccumulationRegister");
    }

    @Test
    void scanRegister_annotatedClassNotExtendingAccumulationRecord_throws() {
        assertThatThrownBy(() -> scanner.scanRegister(NotARegister.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must extend AccumulationRecord");
    }
}
