package su.onno.fixtures;

import su.onno.annotations.AccumulationRegister;
import su.onno.annotations.Dimension;
import su.onno.annotations.Resource;
import su.onno.model.AccumulationRecord;
import su.onno.model.AccumulationType;

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
