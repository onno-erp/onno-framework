package com.onec.fixtures;

import com.onec.annotations.AccumulationRegister;
import com.onec.annotations.Dimension;
import com.onec.annotations.Resource;
import com.onec.model.AccumulationRecord;
import com.onec.model.AccumulationType;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@AccumulationRegister(name = "TestSales", type = AccumulationType.TURNOVER)
@Getter
@Setter
public class TestSalesRegister extends AccumulationRecord {

    @Dimension
    private UUID product;

    @Resource
    private BigDecimal quantity;

    @Resource
    private BigDecimal amount;
}
