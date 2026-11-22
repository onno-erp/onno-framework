package su.onno.web.conformance.model;

import su.onno.annotations.Dimension;
import su.onno.annotations.InformationRegister;
import su.onno.annotations.Attribute;
import su.onno.model.Periodicity;
import su.onno.model.InformationRecord;
import su.onno.types.Ref;

/** An information register scoped by its tenant dimension. */
@InformationRegister(name = "LkTenantFacts", periodicity = Periodicity.NONE)
public class LkTenantFacts extends InformationRecord {

    @Dimension
    private Ref<LkTenant> tenant;

    @Attribute
    private String fact;

    public Ref<LkTenant> getTenant() {
        return tenant;
    }

    public void setTenant(Ref<LkTenant> tenant) {
        this.tenant = tenant;
    }

    public String getFact() {
        return fact;
    }

    public void setFact(String fact) {
        this.fact = fact;
    }
}
