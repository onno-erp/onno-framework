package su.onno.validation;

import org.junit.jupiter.api.Test;
import su.onno.command.RecordRow;
import su.onno.repository.EnumerationPersistence;
import su.onno.types.Ref;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TypedFieldDslTest {

    @Test
    void resolvesTypedValidationFieldsAtTheMetadataBoundary() {
        CheckSpec<Customer> validations = new CheckSpec<>();
        validations.check("availability", ctx -> List.of())
                .dependsOn(Customer::getOrders, Order::getStatus);

        assertThat(validations.build().getFirst().dependencies())
                .containsExactly("orders.status");
    }

    @Test
    void recordRowsAcceptTypedFields() {
        RecordRow<Order> row = new RecordRow<>(java.util.Map.of(
                "status", EnumerationPersistence.resolveId(Status.class, Status.APPROVED),
                "active", true));
        assertThat(row.enumValue(Order::getStatus, Status.class)).isEqualTo(Status.APPROVED);
        assertThat(row.bool(Order::getActive)).isTrue();
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
