package su.onno.ui;

import su.onno.annotations.AccumulationRegister;
import su.onno.annotations.Dimension;
import su.onno.annotations.Resource;
import su.onno.model.AccumulationRecord;
import su.onno.model.AccumulationType;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Minimal turnover register for {@link RegisterQueryServicePostgresIT}: one plain-UUID
 * dimension (so no Ref resolution / catalog lookups are triggered) and one numeric resource.
 */
@AccumulationRegister(name = "TestRevenue", type = AccumulationType.TURNOVER)
public class RevenueRegisterFixture extends AccumulationRecord {

    @Dimension
    private UUID property;

    @Resource
    private BigDecimal amount;
}
