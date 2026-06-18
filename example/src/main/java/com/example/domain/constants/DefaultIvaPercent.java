package com.example.domain.constants;

import su.onno.annotations.Constant;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Default VAT rate for a new {@link com.example.domain.documents.Bill} (Spain's IVA), a single global
 * {@code @Constant} (see {@link CompanyName} for the concept). Each bill carries its own
 * {@code ivaPercent}, so the constant is the starting default, not a hard rule.
 */
@Constant(name = "DefaultIvaPercent")
@Getter
@Setter
public class DefaultIvaPercent {
    private BigDecimal value;
}
