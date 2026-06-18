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
