package su.onno.metadata;

import su.onno.fixtures.NotARegister;
import su.onno.fixtures.TestOverdraftRegister;
import su.onno.fixtures.TestCostRegister;
import su.onno.fixtures.TestStockRegister;
import su.onno.fixtures.TestSalesRegister;
import su.onno.model.AccumulationType;
import su.onno.model.PostingOrder;

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
        assertThat(desc.tableName()).isEqualTo("register_test_stock");
        assertThat(desc.totalsTableName()).isEqualTo("register_test_stock_totals");
        assertThat(desc.javaClass()).isEqualTo(TestStockRegister.class);
        assertThat(desc.accumulationType()).isEqualTo(AccumulationType.BALANCE);
        assertThat(desc.allowNegative()).isFalse();
        assertThat(desc.postingOrder()).isEqualTo(PostingOrder.INDEPENDENT);
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
    void scanRegister_negativeBalancePolicy() {
        AccumulationRegisterDescriptor desc = scanner.scanRegister(TestOverdraftRegister.class);

        assertThat(desc.accumulationType()).isEqualTo(AccumulationType.BALANCE);
        assertThat(desc.allowNegative()).isTrue();
    }

    @Test
    void scanRegister_chronologicalPostingPolicy() {
        AccumulationRegisterDescriptor desc = scanner.scanRegister(TestCostRegister.class);

        assertThat(desc.postingOrder()).isEqualTo(PostingOrder.CHRONOLOGICAL);
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
