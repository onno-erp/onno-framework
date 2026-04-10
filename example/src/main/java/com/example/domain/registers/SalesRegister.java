package com.example.domain.registers;

import com.example.domain.catalogs.Product;
import com.onec.annotations.AccumulationRegister;
import com.onec.annotations.Dimension;
import com.onec.annotations.Resource;
import com.onec.annotations.UiSection;
import com.onec.model.AccumulationRecord;
import com.onec.model.AccumulationType;
import com.onec.types.Ref;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@AccumulationRegister(name = "Sales Register", tableName = "Sales", type = AccumulationType.BALANCE)
@UiSection(value = "Sales", order = 0)
@Getter
@Setter
public class SalesRegister extends AccumulationRecord {

    @Dimension(name = "product")
    private Ref<Product> product;

    @Resource
    private BigDecimal quantity;

    @Resource
    private BigDecimal amount;
}
