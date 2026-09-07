package su.onno.crm.domain;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import su.onno.annotations.*;
import su.onno.model.CatalogObject;
import su.onno.rules.*;
/** Editable status labels, colors, and automatic transition destinations. */
@Catalog(name="CrmConversationStatuses",title="Conversation statuses",codePrefix="CS-",context="CRM")
@AccessControl(readRoles={"CRM_AGENT","CRM_MANAGER"},writeRoles={"CRM_MANAGER"})
@Getter @Setter
public class ChatStatus extends CatalogObject implements Validated {
    @Attribute(displayName="Color",length=7,required=true) private String color="#8B78FF";
    @Attribute(displayName="Conversation is closed") private boolean closed;
    @Attribute(displayName="Use after incoming message") private boolean afterIncoming;
    @Attribute(displayName="Use after sending reply") private boolean afterReply;
    @Attribute(displayName="Use when closing") private boolean afterClose;
    @Attribute(displayName="Use when reopening") private boolean afterReopen;
    @Override public List<BusinessRule> rules() {
        return List.of(
            BusinessRule.onField("description","Enter a status name",()->getDescription()!=null&&!getDescription().isBlank()),
            BusinessRule.onField("color","Choose a valid color",()->color!=null&&color.matches("#[0-9a-fA-F]{6}")),
            BusinessRule.onField("closed","A close destination must be closed; incoming, reply and reopen destinations must be open",()->(!afterClose||closed)&&(!(afterIncoming||afterReply||afterReopen)||!closed)));
    }
}
