package su.onno.crm.web;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import su.onno.crm.domain.Conversation;
import su.onno.crm.service.CrmInboxWorkspace;
import static org.assertj.core.api.Assertions.*;
class CrmInboxFiltersTest {
 @Test void filtersAreOptInAndCombineBeforePagination() {
  var workspace = new CrmInboxWorkspace("support","Support",Set.of("MANAGER"),c->true);
  assertThat(workspace.filters()).isEmpty();
  var configured = workspace.list(list->{
   list.filter(Conversation::getSubject).contains();
   list.filter(Conversation::getLastMessageAt).dateRange();
  });
  var params = new LinkedMultiValueMap<String,String>();
  params.add("like","subject,BOOK"); params.add("ge","lastMessageAt,2026-09-09"); params.add("le","lastMessageAt,2026-09-09");
  var predicate = CrmInboxFilters.predicate(configured.filters(),params);
  assertThat(predicate.test(Map.of("subject","Book enquiry","lastMessageAt","2026-09-09T12:00:00"))).isTrue();
  assertThat(predicate.test(Map.of("subject","Book enquiry","lastMessageAt","2026-09-10T12:00:00"))).isFalse();
  assertThat(predicate.test(Map.of("subject","Something else","lastMessageAt","2026-09-09T12:00:00"))).isFalse();
  assertThatThrownBy(()->CrmInboxFilters.predicate(workspace.filters(),params)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
 }
}
