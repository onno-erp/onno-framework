package su.onno.metadata;

import su.onno.fixtures.TestLabeledStatus;
import su.onno.fixtures.TestOrderStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class EnumerationMetadataScannerTest {

    private MetadataScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new MetadataScanner(new DefaultNamingStrategy());
    }

    @Test
    void scanEnumeration_returnsCorrectDescriptor() {
        EnumerationDescriptor desc = scanner.scanEnumeration(TestOrderStatus.class);

        assertThat(desc.logicalName()).isEqualTo("OrderStatuses");
        assertThat(desc.tableName()).isEqualTo("enum_order_statuses");
        assertThat(desc.javaClass()).isEqualTo(TestOrderStatus.class);
    }

    @Test
    void scanEnumeration_findsAllValues() {
        EnumerationDescriptor desc = scanner.scanEnumeration(TestOrderStatus.class);

        assertThat(desc.values()).hasSize(3);
        assertThat(desc.values().stream().map(EnumerationValueDescriptor::name))
                .containsExactly("NEW", "IN_PROGRESS", "COMPLETED");
    }

    @Test
    void scanEnumeration_valuesHaveStableUUIDs() {
        EnumerationDescriptor desc1 = scanner.scanEnumeration(TestOrderStatus.class);
        EnumerationDescriptor desc2 = scanner.scanEnumeration(TestOrderStatus.class);

        for (int i = 0; i < desc1.values().size(); i++) {
            assertThat(desc1.values().get(i).id()).isEqualTo(desc2.values().get(i).id());
        }
    }

    @Test
    void scanEnumeration_valuesHaveCorrectOrder() {
        EnumerationDescriptor desc = scanner.scanEnumeration(TestOrderStatus.class);

        assertThat(desc.values().get(0).order()).isEqualTo(0);
        assertThat(desc.values().get(1).order()).isEqualTo(1);
        assertThat(desc.values().get(2).order()).isEqualTo(2);
    }

    @Test
    void scanEnumeration_nonEnumClass_throws() {
        assertThatThrownBy(() -> scanner.scanEnumeration(String.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not annotated with @Enumeration");
    }

    @Test
    void scanEnumeration_displayTitle_fallsBackToName() {
        EnumerationDescriptor desc = scanner.scanEnumeration(TestOrderStatus.class);

        assertThat(desc.displayTitle()).isEqualTo("OrderStatuses");
    }

    @Test
    void scanEnumeration_unlabelled_valueLabelEqualsName() {
        EnumerationDescriptor desc = scanner.scanEnumeration(TestOrderStatus.class);

        assertThat(desc.values()).allSatisfy(v -> assertThat(v.label()).isEqualTo(v.name()));
    }

    @Test
    void scanEnumeration_title_usesAnnotationValue() {
        EnumerationDescriptor desc = scanner.scanEnumeration(TestLabeledStatus.class);

        assertThat(desc.displayTitle()).isEqualTo("Статусы заказов");
    }

    @Test
    void scanEnumeration_enumLabel_overridesNameButKeepsConstant() {
        EnumerationDescriptor desc = scanner.scanEnumeration(TestLabeledStatus.class);

        EnumerationValueDescriptor labelled = desc.values().get(0);
        assertThat(labelled.name()).isEqualTo("NEW");
        assertThat(labelled.label()).isEqualTo("Новый");

        // An unlabelled constant keeps name == label, so display still works.
        EnumerationValueDescriptor unlabelled = desc.values().get(2);
        assertThat(unlabelled.name()).isEqualTo("COMPLETED");
        assertThat(unlabelled.label()).isEqualTo("COMPLETED");
    }
}
