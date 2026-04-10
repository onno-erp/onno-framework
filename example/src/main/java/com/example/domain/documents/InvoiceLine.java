package com.example.domain.documents;

import com.onec.annotations.Attribute;
import com.onec.model.TabularSectionRow;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class InvoiceLine extends TabularSectionRow {

    @Attribute(length = 100)
    private String productName;

    @Attribute(precision = 15, scale = 2)
    private BigDecimal quantity;

    @Attribute(precision = 15, scale = 2)
    private BigDecimal price;

    @Attribute(precision = 15, scale = 2)
    private BigDecimal amount;
}
