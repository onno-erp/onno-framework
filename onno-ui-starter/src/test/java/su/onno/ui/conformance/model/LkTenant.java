package su.onno.ui.conformance.model;

import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;
import su.onno.types.Ref;

/** Scoped directly: a customer reads the tenants it owns. */
@Catalog(name = "LkTenants")
@AccessControl(readRoles = {"CUSTOMER", "STAFF"})
public class LkTenant extends CatalogObject {

    @Attribute
    private Ref<LkOwner> owner;

    @Attribute
    private String note;

    public Ref<LkOwner> getOwner() {
        return owner;
    }

    public void setOwner(Ref<LkOwner> owner) {
        this.owner = owner;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
