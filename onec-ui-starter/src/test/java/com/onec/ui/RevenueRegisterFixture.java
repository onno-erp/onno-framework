package com.onec.ui;

import com.onec.annotations.AccumulationRegister;
import com.onec.annotations.Dimension;
import com.onec.annotations.Resource;
import com.onec.model.AccumulationRecord;
import com.onec.model.AccumulationType;

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
