package su.onno.web.relations.model;

import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;
import su.onno.rules.BusinessRule;
import su.onno.rules.Validated;
import su.onno.types.Ref;

import java.util.List;

/** The join entity: a doctor works at a clinic in a role. */
@Catalog(name = "RlClinicDoctors")
@AccessControl(readRoles = {"WRITER", "LIMITED"}, writeRoles = {"WRITER", "LIMITED"})
public class RlClinicDoctor extends CatalogObject implements Validated {
    @Attribute(required = true)
    private Ref<RlClinic> clinic;

    @Attribute(required = true)
    private Ref<RlDoctor> doctor;

    @Attribute(length = 60)
    private String role;

    @Override
    public List<BusinessRule> rules() {
        return List.of(new BusinessRule("role-allowed", "Role is not allowed",
                () -> !"forbidden".equals(role)));
    }
}
