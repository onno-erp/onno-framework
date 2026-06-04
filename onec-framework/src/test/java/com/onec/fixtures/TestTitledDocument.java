package com.onec.fixtures;

import com.onec.annotations.Attribute;
import com.onec.annotations.Document;
import com.onec.model.DocumentObject;

import lombok.Getter;
import lombok.Setter;

/**
 * A document whose URL-safe {@code name} differs from its localized display
 * {@code title} — exercises the display-label-distinct-from-route-key path.
 */
@Document(name = "SupplierOrders", title = "Заказы поставщикам")
@Getter
@Setter
public class TestTitledDocument extends DocumentObject {

    @Attribute(length = 100)
    private String memo;
}
