package su.onno.crm.web;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import su.onno.crm.domain.Conversation;
import su.onno.crm.service.CrmInboxWorkspace;
import static org.assertj.core.api.Assertions.*;
class CrmInboxFiltersTest {
 @Test void relatedStageFiltersMatchAnySelectedValueAndIntersectOtherFilters() {
  var workspace=new CrmInboxWorkspace("stages","Stages",Set.of("MANAGER"),c->true)
    .list(list->list.filter(Conversation::getSubject).contains())
    .facet(new CrmInboxWorkspace.Facet("stage","Pipeline stage",List.of(new CrmInboxWorkspace.FacetOption("WON","Won","#10B981")),(rows,principal)->Map.of()));
  var params=new LinkedMultiValueMap<String,String>();params.add("in","stage,WON");params.add("in","stage,NEW");params.add("like","subject,booking");
  var matches=CrmInboxFilters.predicate(workspace.filters(),params);
  assertThat(matches.test(Map.of("stage",List.of("LOST","WON"),"subject","Booking enquiry"))).isTrue();
  assertThat(matches.test(Map.of("stage",List.of("LOST"),"subject","Booking enquiry"))).isFalse();
  assertThat(matches.test(Map.of("stage",List.of("WON"),"subject","Something else"))).isFalse();
  assertThat(matches.test(Map.of("stage",List.of(),"subject","Booking enquiry"))).isFalse();
  assertThatThrownBy(()->workspace.facet(workspace.facets().getFirst())).isInstanceOf(IllegalArgumentException.class);
 }
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
