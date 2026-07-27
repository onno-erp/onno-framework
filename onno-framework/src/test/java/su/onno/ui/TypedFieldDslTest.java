package su.onno.ui;

import org.junit.jupiter.api.Test;
import su.onno.repository.EnumerationPersistence;
import su.onno.types.Ref;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TypedFieldDslTest {

    @Test
    void resolvesTypedListRelatedAndValidationFieldsAtTheMetadataBoundary() {
        ListSpec<Order> list = new ListSpec<>();
        list.columns(Order::getStatus, Order::getTotal)
                .sortBy(Order::getTotal, true)
                .filter(Order::getStatus).multiOptions();
        assertThat(list.include()).containsExactly("status", "total");
        assertThat(list.sortField()).isEqualTo("total");
        assertThat(list.sortDescending()).isTrue();
        assertThat(list.filters()).extracting(ListSpec.Filter::field)
                .containsExactly("status");

        EntityConfigBuilder<Customer> fields = new EntityConfigBuilder<>();
        fields.refField(Customer::getAccount).refSecondary(Account::getEmail);
        fields.relatedList("orders", Order.class)
                .via(Order::getCustomer)
                .display(Order::getAccount)
                .columns(Order::getStatus, Order::getTotal);
        fields.validation("availability", NoopValidator.class)
                .dependsOn(Customer::getOrders, Order::getStatus);

        assertThat(fields.buildFieldHints().get("account").refSecondary()).isEqualTo("email");
        assertThat(fields.buildRelatedLists().getFirst())
                .extracting(RelatedList::via, RelatedList::display, RelatedList::columns)
                .containsExactly("customer", "account", List.of("status", "total"));
        assertThat(fields.buildValidations().getFirst().dependencies())
                .containsExactly("orders.status");
    }

    @Test
    void actionRowsAcceptTypedFields() {
        ActionRow row = new ActionRow(java.util.Map.of(
                "status", EnumerationPersistence.resolveId(Status.class, Status.APPROVED),
                "active", true));
        assertThat(row.enumValue(Order::getStatus, Status.class)).isEqualTo(Status.APPROVED);
        assertThat(row.bool(Order::getActive)).isTrue();
    }

    static final class NoopValidator implements FormValidator {
        @Override
        public List<FormFeedback> validate(FormValidationContext context) {
            return List.of();
        }
    }

    static final class Customer {
        private Ref<Account> account;
        private List<Order> orders;
        public Ref<Account> getAccount() { return account; }
        public List<Order> getOrders() { return orders; }
    }

    static final class Account {
        private String email;
        public String getEmail() { return email; }
    }

    static final class Order {
        private Ref<Customer> customer;
        private Ref<Account> account;
        private Status status;
        private Integer total;
        private Boolean active;
        public Ref<Customer> getCustomer() { return customer; }
        public Ref<Account> getAccount() { return account; }
        public Status getStatus() { return status; }
        public Integer getTotal() { return total; }
        public Boolean getActive() { return active; }
    }

    enum Status { NEW, APPROVED }
}
