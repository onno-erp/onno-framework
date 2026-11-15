package su.onno.ui.tags;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.PayloadApplicationEvent;
import su.onno.events.EntityChangedEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TagServiceTest {
    TagService tags;
    private Jdbi jdbi;
    private final List<EntityChangedEvent> publishedEvents = new ArrayList<>();

    @BeforeEach
    void setup() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:tags" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbi = Jdbi.create(ds);
        tags = new TagService(jdbi, List.of(), publishedEvents::add, scope -> "Customers");
    }

    @Test
    void publishesOneEventPerChangeThroughTheService() {
        String scope = TagService.scope("catalogs", "Customers");
        UUID record = UUID.randomUUID();
        var tag = tags.create(scope, "VIP", null);
        tags.create(scope, "vip", null);
        tags.assign(scope, record, tag.id());
        tags.assign(scope, record, tag.id());
        tags.remove(scope, record, tag.id());
        tags.remove(scope, record, tag.id());

        assertThat(publishedEvents).containsExactly(
                new EntityChangedEvent(EntityChangedEvent.UPDATED, "tag", "Customers", null, null),
                new EntityChangedEvent(EntityChangedEvent.UPDATED, "tag", "Customers", record, null),
                new EntityChangedEvent(EntityChangedEvent.UPDATED, "tag", "Customers", record, null));
    }

    @Test
    void eventsFollowTheOutcomeOfAnOuterJdbiTransaction() {
        String scope = TagService.scope("catalogs", "Customers");
        UUID record = UUID.randomUUID();

        jdbi.useTransaction(handle -> {
            var tag = tags.create(scope, "VIP", null);
            tags.assign(scope, record, tag.id());
            tags.remove(scope, record, tag.id());
            handle.rollback();
        });
        assertThat(publishedEvents).isEmpty();
        assertThat(tags.library(scope)).isEmpty();

        jdbi.useTransaction(handle -> {
            var tag = tags.create(scope, "VIP", null);
            tags.assign(scope, record, tag.id());
            tags.remove(scope, record, tag.id());
            assertThat(publishedEvents).isEmpty();
        });
        assertThat(publishedEvents).hasSize(3);
    }

    @Test
    void forwardsExternalCatalogChangesAndSuppressesTheEchoFromCreatingATag() {
        String scope = TagService.scope("catalogs", "Customers");
        var catalog = mock(TagCatalog.class);
        when(catalog.scope()).thenReturn(scope);
        when(catalog.catalogName()).thenReturn("CustomerTags");
        when(catalog.list()).thenReturn(List.of());
        var tag = new TagService.Tag(UUID.randomUUID(), "VIP", "#8b78ff");
        var catalogEvent = new EntityChangedEvent(
                EntityChangedEvent.CREATED, EntityChangedEvent.CATALOG, "CustomerTags", tag.id(), null);
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("catalog", catalog);
        tags = new TagService(jdbi, List.of(catalog), publishedEvents::add, ignored -> "Customers");
        var relay = new TagsAutoConfiguration().tagCatalogChanges(
                tags, beans.getBeanProvider(TagCatalog.class), event -> publishedEvents.add((EntityChangedEvent) event));
        when(catalog.create("VIP", "#8b78ff")).thenAnswer(invocation -> {
            relay.onApplicationEvent(new PayloadApplicationEvent<>(this, catalogEvent));
            return tag;
        });

        assertThat(tags.create(scope, "VIP", null)).isEqualTo(tag);
        assertThat(publishedEvents).containsExactly(
                new EntityChangedEvent(EntityChangedEvent.UPDATED, "tag", "Customers", null, null));

        relay.onApplicationEvent(new PayloadApplicationEvent<>(this, catalogEvent));
        assertThat(publishedEvents.getLast()).isEqualTo(
                new EntityChangedEvent(EntityChangedEvent.UPDATED, "tag", "CustomerTags", tag.id(), null));
    }
    @Test void reusesIdsAndKeepsAssignmentsIndependent() {
        String scope=TagService.scope("catalogs","Customers"); var first=UUID.randomUUID(); var second=UUID.randomUUID();
        var vip=tags.create(scope," VIP ","#8b78ff");
        assertThat(tags.create(scope,"vip",null).id()).isEqualTo(vip.id());
        tags.assign(scope,first,vip.id()); tags.assign(scope,first,vip.id()); tags.assign(scope,second,vip.id());
        assertThat(tags.assigned(scope,first)).containsExactly(vip);
        tags.remove(scope,first,vip.id());
        assertThat(tags.assigned(scope,first)).isEmpty(); assertThat(tags.assigned(scope,second)).containsExactly(vip);
        assertThat(tags.library(scope)).containsExactly(vip);
    }
    @Test void rejectsForeignTagsAndInvalidDefinitions() {
        var tag=tags.create("catalogs:customers","VIP",null);
        assertThatThrownBy(() -> tags.assign("documents:orders",UUID.randomUUID(),tag.id())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tags.create("catalogs:customers"," ",null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tags.create("catalogs:customers","VIP","red")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void importsLegacyOnlyOnceAndPreservesNamesWithCommasInNewTags() {
        var id=UUID.randomUUID(); String scope="catalogs:customers";
        tags.importLegacy(scope,id," VIP, Follow up, VIP ");
        assertThat(tags.assigned(scope,id)).hasSize(2);
        var vip=tags.assigned(scope,id).stream().filter(t -> t.name().equals("VIP")).findFirst().orElseThrow();
        tags.remove(scope,id,vip.id()); tags.importLegacy(scope,id,"VIP, Follow up");
        assertThat(tags.assigned(scope,id)).hasSize(1);
        var named=tags.create(scope,"Sales, Europe",null); tags.assign(scope,id,named.id());
        assertThat(tags.assigned(scope,id)).contains(named);
    }
}
