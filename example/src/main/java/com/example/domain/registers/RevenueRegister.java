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
 * Billed revenue per property — a {@link AccumulationType#TURNOVER} register (period totals, not a
 * balance). {@link com.example.domain.documents.Bill} posts net / IVA / gross here, so the
 * dashboard's "Revenue by property" and "Revenue over time" widgets can chart it without scanning
 * the source documents. Sliced by {@code property} ({@code @Dimension}); the money columns are the
 * accumulated {@code @Resource}s.
 */
@AccumulationRegister(name = "Revenue", type = AccumulationType.TURNOVER, context = "Rentals")
@AccessControl(readRoles = {"RENTALS", "FINANCE"})
@Getter
@Setter
public class RevenueRegister extends AccumulationRecord {

    @Dimension
    private Ref<Property> property;

    @Resource(precision = 14, scale = 2)
    private BigDecimal netAmount;

    @Resource(precision = 14, scale = 2)
    private BigDecimal ivaAmount;

    @Resource(precision = 14, scale = 2)
    private BigDecimal grossAmount;
}
