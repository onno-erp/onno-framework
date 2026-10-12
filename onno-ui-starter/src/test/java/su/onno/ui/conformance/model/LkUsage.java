package su.onno.ui.conformance.model;

import su.onno.annotations.AccessControl;
import su.onno.annotations.AccumulationRegister;
import su.onno.annotations.Dimension;
import su.onno.annotations.Resource;
import su.onno.model.AccumulationRecord;
import su.onno.model.AccumulationType;
import su.onno.types.Ref;

import java.math.BigDecimal;

/** A balance register scoped by its tenant dimension (movements and the totals table). */
@AccumulationRegister(name = "LkUsage", type = AccumulationType.BALANCE)
@AccessControl(readRoles = {"CUSTOMER", "STAFF"})
public class LkUsage extends AccumulationRecord {

    @Dimension
    private Ref<LkTenant> tenant;

    @Resource(precision = 12, scale = 2)
    private BigDecimal minutes;

    public Ref<LkTenant> getTenant() {
        return tenant;
    }

    public void setTenant(Ref<LkTenant> tenant) {
        this.tenant = tenant;
    }

    public BigDecimal getMinutes() {
        return minutes;
    }

    public void setMinutes(BigDecimal minutes) {
        this.minutes = minutes;
    }
}
