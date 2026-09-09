package su.onno.crm.service;
import java.util.*;
import org.junit.jupiter.api.Test;
import su.onno.annotations.*;
import su.onno.model.CatalogObject;
import su.onno.repository.EnumerationPersistence;
import static org.assertj.core.api.Assertions.*;
class CrmStateConfigurationTest {
 @su.onno.annotations.Enumeration(name="SupportStates") enum State {
  @EnumLabel(value="Needs attention",color="#123456") OPEN,
  @EnumLabel("Done") CLOSED
 }
 @Catalog(name="HostStatuses") static class Status extends CatalogObject {}
 @Test void usesNormalEnumerationIdentityAndLabels() {
  var open=EnumerationPersistence.resolveId(State.class,State.OPEN);
  var closed=EnumerationPersistence.resolveId(State.class,State.CLOSED);
  var config=CrmStateConfiguration.enumeration(State.class,s->s==State.CLOSED,
    new CrmStateConfiguration.Transitions(open,open,closed,open));
  var statuses=new CrmConversationStatuses(config);
  assertThat(statuses.incoming()).isEqualTo(open);
  assertThat(statuses.close()).isEqualTo(closed);
  assertThat(config.conversationStatuses().getFirst().choice().label()).isEqualTo("Needs attention");
  assertThatThrownBy(()->statuses.active(UUID.randomUUID())).hasMessageContaining("configured");
 }
 @Test void readsLiveHostCatalogWithoutCopyingRowsAndRejectsDeletedChoices() {
  var row=new Status();row.setId(UUID.randomUUID());row.setDescription("Waiting");
  var config=CrmStateConfiguration.catalog(Status.class,()->List.of(row),r->null,r->false,null);
  assertThat(config.source()).isEqualTo("HostStatuses");
  row.setDescription("Review");
  assertThat(config.conversationStatuses().getFirst().choice().label()).isEqualTo("Review");
  row.setDeletionMark(true);
  assertThat(config.conversationStatuses()).isEmpty();
  assertThatThrownBy(()->new CrmConversationStatuses(config).active(row.getId())).hasMessageContaining("configured");
  assertThat(CrmStateConfiguration.empty().source()).isEmpty();
 }
}
