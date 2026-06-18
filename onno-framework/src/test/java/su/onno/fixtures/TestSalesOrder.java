package su.onno.fixtures;

import su.onno.annotations.Attribute;
import su.onno.annotations.Document;
import su.onno.model.DocumentObject;
import su.onno.types.Ref;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Document(name = "TestSalesOrders", numberLength = 11)
@Getter
@Setter
public class TestSalesOrder extends DocumentObject {

    @Attribute
    private Ref<TestCustomer> customer;

    @Attribute(length = 20)
    private String status;

    @Attribute(precision = 15, scale = 2)
    private BigDecimal amount;
}
