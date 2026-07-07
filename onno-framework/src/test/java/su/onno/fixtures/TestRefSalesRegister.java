package su.onno.fixtures;

import su.onno.annotations.AccumulationRegister;
import su.onno.annotations.Dimension;
import su.onno.annotations.Resource;
import su.onno.model.AccumulationRecord;
import su.onno.model.AccumulationType;
import su.onno.types.Ref;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@AccumulationRegister(name = "TestRefSales", type = AccumulationType.TURNOVER)
@Getter
@Setter
public class TestRefSalesRegister extends AccumulationRecord {

    @Dimension
    private Ref<TestProduct> product;

    @Resource
    private BigDecimal amount;
}
