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

@AccumulationRegister(name = "TestStock", type = AccumulationType.BALANCE)
@Getter
@Setter
public class TestStockRegister extends AccumulationRecord {

    @Dimension
    private UUID product;

    @Dimension
    private UUID warehouse;

    @Resource
    private BigDecimal quantity;
}
