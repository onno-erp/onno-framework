package com.example.domain.registers;

import com.onec.annotations.AccumulationRegister;
import com.onec.annotations.Dimension;
import com.onec.annotations.Resource;
import com.onec.model.AccumulationRecord;
import com.onec.model.AccumulationType;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@AccumulationRegister(name = "Sales", type = AccumulationType.BALANCE)
@Getter
@Setter
public class SalesRegister extends AccumulationRecord {

    @Dimension(name = "product_name")
    private String productName;

    @Resource
    private BigDecimal quantity;

    @Resource
    private BigDecimal amount;
}
