package su.onno.ui;

import org.junit.jupiter.api.Test;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataScanner;
import su.onno.model.CatalogObject;
import su.onno.repository.EnumerationPersistence;
import su.onno.types.Ref;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Storage rows must resolve typed properties through metadata, not a guessed SQL name. */
class ActionRowCamelCaseTest {

    private static final UUID EMPLOYEE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static ActionRow row(Map<String, Object> values) {
        return new ActionRow(values, new MetadataScanner(new DefaultNamingStrategy())
                .scan(Order.class).attributes());
    }

    @Test
    void readsRawReferenceUsingACamelCaseGetter() {
        assertThat(row(Map.of("assigned_to", EMPLOYEE_ID)).get(Order::getAssignedTo))
                .isEqualTo(EMPLOYEE_ID);
    }

    @Test
    void readsResolvedReferenceLabelUsingACamelCaseGetter() {
        assertThat(row(Map.of("assigned_to", EMPLOYEE_ID,
                "assigned_to_display", "Assigned employee")).text(Order::getAssignedTo))
                .isEqualTo("Assigned employee");
    }

    @Test
    void readsBooleanUsingACamelCaseGetter() {
        assertThat(row(Map.of("needs_approval", true)).bool(Order::getNeedsApproval)).isTrue();
    }

    @Test
    void resolvesEnumerationUsingACamelCaseGetter() {
        UUID status = EnumerationPersistence.resolveId(ReviewStatus.class, ReviewStatus.APPROVED);
        assertThat(row(Map.of("review_status", status))
                .enumValue(Order::getReviewStatus, ReviewStatus.class)).isEqualTo(ReviewStatus.APPROVED);
    }

    @Test
    void respectsExplicitColumnNamesInsteadOfGuessingSnakeCase() {
        ActionRow row = row(Map.of("approval_flag", true, "custom_flag", false));
        assertThat(row.bool(Order::getCustomFlag)).isTrue();
        assertThat(row.bool("custom_flag")).isFalse();
        assertThat(row.bool("approval_flag")).isTrue();
    }

    @Test
    void metadataLookupRetainsCaseInsensitiveStorageReadsAndDisplayFallback() {
        ActionRow row = row(Map.of("ASSIGNED_TO", EMPLOYEE_ID, "NEEDS_APPROVAL", "t"));
        assertThat(row.get(Order::getAssignedTo)).isEqualTo(EMPLOYEE_ID);
        assertThat(row.text(Order::getAssignedTo)).isEqualTo(EMPLOYEE_ID.toString());
        assertThat(row.bool(Order::getNeedsApproval)).isTrue();
    }

    @Test
    void absentValuesKeepTheirExistingDefaults() {
        ActionRow row = row(Map.of());
        assertThat(row.get(Order::getAssignedTo)).isNull();
        assertThat(row.text(Order::getAssignedTo)).isEmpty();
        assertThat(row.bool(Order::getNeedsApproval)).isFalse();
        assertThat(row.enumValue(Order::getReviewStatus, ReviewStatus.class)).isNull();
    }

    @Test
    void originalConstructorStillAcceptsLogicalKeysWithoutChangingTheMap() {
        Map<String, Object> values = Map.of("assignedTo", EMPLOYEE_ID);
        ActionRow row = new ActionRow(values);
        assertThat(row.get(Order::getAssignedTo)).isEqualTo(EMPLOYEE_ID);
        assertThat(row.values()).isSameAs(values);
        assertThat(new ActionRow(null).values()).isEmpty();
    }

    @Catalog(name = "ActionRowOrders")
    static final class Order extends CatalogObject {
        @Attribute
        private Ref<Employee> assignedTo;
        @Attribute
        private Boolean needsApproval;
        @Attribute
        private ReviewStatus reviewStatus;
        @Attribute(name = "approval_flag")
        private Boolean customFlag;

        public Ref<Employee> getAssignedTo() { return assignedTo; }
        public Boolean getNeedsApproval() { return needsApproval; }
        public ReviewStatus getReviewStatus() { return reviewStatus; }
        public Boolean getCustomFlag() { return customFlag; }
    }

    @Catalog(name = "ActionRowEmployees")
    static final class Employee extends CatalogObject {}

    enum ReviewStatus { NEW, APPROVED }
}
