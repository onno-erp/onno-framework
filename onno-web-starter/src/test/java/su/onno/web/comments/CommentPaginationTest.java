package su.onno.web.comments;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CommentPaginationTest {
    @Test void threadPagesKeepTiesSkipDeletedAndRejectAnotherThreadOrSubject() {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");
        var jdbi=Jdbi.create(ds);var service=new CommentService(jdbi);UUID target=UUID.randomUUID();
        List<UUID> ids=new ArrayList<>();
        for(int i=0;i<5;i++) ids.add(service.add("catalogs","Clients",target,null,"Alice","text").id());
        jdbi.useHandle(h -> h.execute("UPDATE onno_comments SET _created_at = TIMESTAMP '2026-11-10 00:00:00'"));
        service.softDelete(ids.get(2));
        var first=service.page("catalogs","Clients",target,null,2,"alice");
        service.add("catalogs","Clients",target,null,"Alice","inserted later");
        var rest=service.page("catalogs","Clients",target,first.nextCursor(),100,"alice");
        var seen=new HashSet<>(first.rows().stream().map(Comment::id).toList());
        rest.rows().forEach(r -> assertThat(seen.add(r.id())).isTrue());
        assertThat(seen).hasSize(5).doesNotContain(ids.get(2));
        assertThat(rest.nextCursor()).isNull();assertThat(rest.hasMore()).isFalse();
        assertThat(service.page("catalogs","Clients",target,"broken",2,"alice").rows()).isEqualTo(first.rows());
        assertThatThrownBy(() -> service.page("catalogs","Clients",UUID.randomUUID(),first.nextCursor(),2,"alice"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.page("catalogs","Clients",target,first.nextCursor(),2,"bob"))
                .isInstanceOf(ResponseStatusException.class);
    }
}
