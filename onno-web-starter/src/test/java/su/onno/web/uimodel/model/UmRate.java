package su.onno.web.uimodel.model;

import su.onno.annotations.AccessControl;
import su.onno.annotations.Dimension;
import su.onno.annotations.InformationRegister;
import su.onno.annotations.Resource;
import su.onno.model.InformationRecord;
import su.onno.model.Periodicity;

import java.math.BigDecimal;

/** A periodic information register readable by READER and WRITER, never by HR. */
@InformationRegister(name = "UmRates", title = "Rates", periodicity = Periodicity.DAY, context = "Sales")
@AccessControl(readRoles = {"READER", "WRITER"})
public class UmRate extends InformationRecord {
    @Dimension
    private String currency;

    @Resource
    private BigDecimal rate;
}
