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

@AccumulationRegister(
        name = "TestOverdraft",
        type = AccumulationType.BALANCE,
        allowNegative = true)
@Getter
@Setter
public class TestOverdraftRegister extends AccumulationRecord {

    @Dimension
    private UUID account;

    @Resource
    private BigDecimal amount;
}
