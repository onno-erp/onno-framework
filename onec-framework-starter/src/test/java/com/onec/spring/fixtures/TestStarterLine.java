package com.onec.spring.fixtures;

import com.onec.annotations.Attribute;
import com.onec.model.TabularSectionRow;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class TestStarterLine extends TabularSectionRow {

    @Attribute(length = 100)
    private String productName;

    @Attribute(precision = 15, scale = 2)
    private BigDecimal quantity;
}
