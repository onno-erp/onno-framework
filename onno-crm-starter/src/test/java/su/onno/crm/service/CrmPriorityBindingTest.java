package su.onno.crm.service;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class CrmPriorityBindingTest {
 @su.onno.annotations.Enumeration(name="HostUrgency") enum Urgency { ROUTINE, ESCALATED }
 @Test void prioritiesAreOptionalAndUseTheHostsNormalEnumeration() {
  assertThat(new su.onno.crm.domain.Conversation().getPriority()).isNull();
  assertThat(CrmPriorityBinding.empty().options()).isEmpty();
  var binding=CrmPriorityBinding.enumeration(Urgency.class);
  assertThat(binding.options()).containsEntry(su.onno.repository.EnumerationPersistence.resolveId(Urgency.class,Urgency.ESCALATED).toString(),"ESCALATED");
  assertThat(binding.choices().source()).isEqualTo("HostUrgency");
 }
}
