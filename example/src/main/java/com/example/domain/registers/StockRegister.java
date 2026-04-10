package com.example.domain.registers;

import com.example.domain.catalogs.Product;
import com.example.domain.catalogs.Warehouse;
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

@AccumulationRegister(name = "Stock Register", tableName = "Stock", type = AccumulationType.BALANCE)
@UiSection(value = "Warehouse", order = 1)
@Getter
@Setter
public class StockRegister extends AccumulationRecord {

    @Dimension(name = "warehouse")
    private Ref<Warehouse> warehouse;

    @Dimension(name = "product")
    private Ref<Product> product;

    @Resource
    private BigDecimal quantity;
}
