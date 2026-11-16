package su.onno.crm.web;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import su.onno.crm.domain.Conversation;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.MetadataScanner;
import static org.assertj.core.api.Assertions.*;
class CrmInboxFiltersTest {
 static final Map<String,CrmInboxFilters.Kind> FIELDS=CrmInboxFilters.fields(new MetadataScanner(new DefaultNamingStrategy()).scan(Conversation.class),List.of("stage"));
 @Test void everyConversationFieldIsFilterableWithTheOperatorsItsTypeSupports() {
  assertThat(FIELDS).containsEntry("subject",CrmInboxFilters.Kind.TEXT).containsEntry("lastMessageAt",CrmInboxFilters.Kind.DATE)
    .containsEntry("unreadCount",CrmInboxFilters.Kind.NUMBER).containsEntry("status",CrmInboxFilters.Kind.VALUE).containsEntry("stage",CrmInboxFilters.Kind.VALUE);
  var range=new LinkedMultiValueMap<String,String>();range.add("ge","status,x");
  assertThatThrownBy(()->CrmInboxFilters.predicate(FIELDS,range)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  var unknown=new LinkedMultiValueMap<String,String>();unknown.add("eq","nope,x");
  assertThatThrownBy(()->CrmInboxFilters.predicate(FIELDS,unknown)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
 }
 @Test void facetFiltersMatchAnySelectedValueAndIntersectOtherFilters() {
  var params=new LinkedMultiValueMap<String,String>();params.add("in","stage,WON");params.add("in","stage,NEW");params.add("like","subject,booking");
  var matches=CrmInboxFilters.predicate(FIELDS,params);
  assertThat(matches.test(Map.of("stage",List.of("LOST","WON"),"subject","Booking enquiry"))).isTrue();
  assertThat(matches.test(Map.of("stage",List.of("LOST"),"subject","Booking enquiry"))).isFalse();
  assertThat(matches.test(Map.of("stage",List.of("WON"),"subject","Something else"))).isFalse();
  assertThat(matches.test(Map.of("stage",List.of(),"subject","Booking enquiry"))).isFalse();
 }
 @Test void filtersCombineBeforePagination() {
  var params = new LinkedMultiValueMap<String,String>();
  params.add("like","subject,BOOK"); params.add("ge","lastMessageAt,2026-09-09"); params.add("le","lastMessageAt,2026-09-09");
  var predicate = CrmInboxFilters.predicate(FIELDS,params);
  assertThat(predicate.test(Map.of("subject","Book enquiry","lastMessageAt","2026-09-09T12:00:00"))).isTrue();
  assertThat(predicate.test(Map.of("subject","Book enquiry","lastMessageAt","2026-09-10T12:00:00"))).isFalse();
  assertThat(predicate.test(Map.of("subject","Something else","lastMessageAt","2026-09-09T12:00:00"))).isFalse();
 }
 @Test void numberRangeBoundsCompareNumericallyNotLexically() {
  var params = new LinkedMultiValueMap<String,String>();
  params.add("ge","unreadCount,10"); params.add("le","unreadCount,50.5");
  var predicate = CrmInboxFilters.predicate(FIELDS,params);
  assertThat(predicate.test(Map.of("unreadCount",10))).isTrue();
  assertThat(predicate.test(Map.of("unreadCount",new java.math.BigDecimal("50.50")))).isTrue();
  assertThat(predicate.test(Map.of("unreadCount",9))).isFalse();
  assertThat(predicate.test(Map.of("unreadCount",100))).isFalse();
  var open = new LinkedMultiValueMap<String,String>(); open.add("le","unreadCount,abc");
  assertThat(CrmInboxFilters.predicate(FIELDS,open).test(Map.of("unreadCount",1))).isFalse();
 }
}
