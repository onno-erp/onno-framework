package su.onno.ui.conformance.model;

import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;

/** The identity catalog of the leak-test app: a signed-in customer is one owner. */
@Catalog(name = "LkOwners")
@AccessControl(readRoles = {"CUSTOMER", "STAFF"})
public class LkOwner extends CatalogObject {

    @Attribute
    private String email;

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }
}
