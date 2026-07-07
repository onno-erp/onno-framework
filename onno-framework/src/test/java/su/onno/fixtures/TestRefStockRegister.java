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

@AccumulationRegister(name = "TestRefStock", type = AccumulationType.BALANCE)
@Getter
@Setter
public class TestRefStockRegister extends AccumulationRecord {

    @Dimension
    private Ref<TestProduct> product;

    @Dimension
    private String currency;

    @Resource
    private BigDecimal quantity;
}
