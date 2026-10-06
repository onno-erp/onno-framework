package su.onno.ui.conformance.model;

import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;
import su.onno.types.Ref;

/**
 * Scoped through a ref ({@code via("tenant")}), hierarchical, and carrying a second, unscoped-by
 * policy ref ({@code mirror}) that may point at another owner's tenant — the restricted-ref case.
 */
@Catalog(name = "LkInstances", hierarchical = true)
@AccessControl(readRoles = {"CUSTOMER", "STAFF"})
public class LkInstance extends CatalogObject {

    @Attribute
    private Ref<LkTenant> tenant;

    @Attribute
    private Ref<LkPlan> plan;

    @Attribute
    private Ref<LkTenant> mirror;

    public Ref<LkTenant> getTenant() {
        return tenant;
    }

    public void setTenant(Ref<LkTenant> tenant) {
        this.tenant = tenant;
    }

    public Ref<LkPlan> getPlan() {
        return plan;
    }

    public void setPlan(Ref<LkPlan> plan) {
        this.plan = plan;
    }

    public Ref<LkTenant> getMirror() {
        return mirror;
    }

    public void setMirror(Ref<LkTenant> mirror) {
        this.mirror = mirror;
    }
}
