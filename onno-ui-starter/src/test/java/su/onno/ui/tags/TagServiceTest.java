package su.onno.ui.tags;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class TagServiceTest {
    TagService tags;
    @BeforeEach void setup() { var ds=new JdbcDataSource(); ds.setURL("jdbc:h2:mem:tags"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1"); tags=new TagService(Jdbi.create(ds)); }
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
