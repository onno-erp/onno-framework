package com.onec.fixtures;

import com.onec.annotations.Attribute;
import com.onec.model.TabularSectionRow;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
public class TestReceiptLine extends TabularSectionRow {

    @Attribute
    private UUID product;

    @Attribute(precision = 15, scale = 2)
    private BigDecimal quantity;
}
