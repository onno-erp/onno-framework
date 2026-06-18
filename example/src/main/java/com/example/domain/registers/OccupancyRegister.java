package com.example.domain.registers;

import com.example.domain.catalogs.Property;
import su.onno.annotations.AccessControl;
import su.onno.annotations.AccumulationRegister;
import su.onno.annotations.Dimension;
import su.onno.annotations.Resource;
import su.onno.model.AccumulationRecord;
import su.onno.model.AccumulationType;
import su.onno.types.Ref;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Occupancy totals per property — a {@link AccumulationType#TURNOVER} register: it sums period
 * activity (how many nights/guests in a span), not a running balance. {@link com.example.domain.documents.Booking}
 * writes into it on posting (one {@code addReceipt} per booking).
 *
 * <p>A register is a typed ledger. A {@code @Dimension} is what you slice by (here, the property);
 * a {@code @Resource} is a number that accumulates (nights, adults, children). The framework
 * generates the table and the server-side aggregation behind {@code /api/registers/Occupancy},
 * which the Reports nav entry and the dashboard charts read.</p>
 */
@AccumulationRegister(name = "Occupancy", type = AccumulationType.TURNOVER, context = "Rentals")
@AccessControl(readRoles = {"RENTALS", "FINANCE"})
@Getter
@Setter
public class OccupancyRegister extends AccumulationRecord {

    @Dimension
    private Ref<Property> property;

    @Resource(precision = 6, scale = 0)
    private BigDecimal nights;

    @Resource(precision = 6, scale = 0)
    private BigDecimal adults;

    @Resource(precision = 6, scale = 0)
    private BigDecimal children;
}
