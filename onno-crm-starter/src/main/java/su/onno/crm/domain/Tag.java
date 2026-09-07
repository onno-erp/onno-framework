package su.onno.crm.domain;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import su.onno.annotations.*;
import su.onno.model.CatalogObject;
import su.onno.rules.*;

@Catalog(name="CrmTags", title="Tags", codePrefix="TAG-", context="CRM")
@AccessControl(readRoles={"CRM_AGENT","CRM_MANAGER"},writeRoles={"CRM_MANAGER"})
@Getter @Setter
public class Tag extends CatalogObject implements Validated {
    @Attribute(displayName="Color",length=7,required=true)
    private String color="#8b78ff";
    @Override public List<BusinessRule> rules() {
        return List.of(
            BusinessRule.onField("description","Enter a tag name",()->getDescription()!=null&&!getDescription().isBlank()),
            BusinessRule.onField("color","Choose a valid color",()->color!=null&&color.matches("#[0-9a-fA-F]{6}")));
    }
}
