package su.onno.fixtures;

import su.onno.annotations.AccumulationRegister;
import su.onno.annotations.Dimension;
import su.onno.annotations.Resource;
import su.onno.model.AccumulationRecord;
import su.onno.model.AccumulationType;
import su.onno.model.PostingOrder;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@AccumulationRegister(
        name = "TestCost",
        type = AccumulationType.BALANCE,
        postingOrder = PostingOrder.CHRONOLOGICAL)
@Getter
@Setter
public class TestCostRegister extends AccumulationRecord {

    @Dimension
    private UUID product;

    @Resource(precision = 15, scale = 3)
    private BigDecimal quantity;

    @Resource(precision = 15, scale = 2)
    private BigDecimal amount;
}
