package su.onno.crm.web;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import su.onno.crm.domain.Conversation;
import su.onno.crm.domain.ConversationMessage;
import su.onno.crm.repository.ConversationRepository;
import su.onno.metadata.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CrmFeedQueryTest {
    Jdbi jdbi; CrmFeedQuery query; ConversationRepository repository;
    final UUID visible=new UUID(0,1), hidden=new UUID(0,2);
    String conversationTable,messageTable;
    @BeforeEach void setup() {
        var ds=new JdbcDataSource(); ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");
        jdbi=Jdbi.create(ds); var registry=new MetadataRegistry(); var scanner=new MetadataScanner(new DefaultNamingStrategy());
        var c=scanner.scan(Conversation.class);var m=scanner.scan(ConversationMessage.class);
        registry.registerCatalog(c);registry.registerCatalog(m);conversationTable=c.tableName();messageTable=m.tableName();
        repository=mock(ConversationRepository.class);query=new CrmFeedQuery(jdbi,repository,registry);
        jdbi.useHandle(h->{
            h.execute("CREATE TABLE "+conversationTable+" (_id UUID PRIMARY KEY, _deletion_mark BOOLEAN)");
            h.execute("CREATE TABLE "+messageTable+" (_id UUID PRIMARY KEY, _deletion_mark BOOLEAN, conversation UUID, sent_at TIMESTAMP)");
            h.execute("CREATE TABLE onno_comments (_id UUID PRIMARY KEY, _entity_id UUID, _created_at TIMESTAMP, _deleted BOOLEAN, _entity_type VARCHAR, _entity_name VARCHAR)");
            for(int i=1;i<=5;i++)h.execute("INSERT INTO "+messageTable+" VALUES (?,false,?, TIMESTAMP '2026-11-11 12:30:20.123456')",new UUID(0,i),visible);
            h.execute("INSERT INTO "+messageTable+" VALUES (?,false,?, TIMESTAMP '2026-11-12 12:00:00')",new UUID(0,100),hidden);
            h.execute("INSERT INTO onno_comments VALUES (?, ?, TIMESTAMP '2026-11-11 12:30:20.123456',false,'catalogs','crm_conversations')",new UUID(0,6),visible);
        });
    }
    // This test creates its own in-memory H2 datasource; no IDE datasource is needed.
    @SuppressWarnings("SqlNoDataSourceInspection")
    @Test void singleConnectionPoolSupportsNestedSpringReadsAndCleansProjection() {
        try (var pool = new com.zaxxer.hikari.HikariDataSource()) {
            pool.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
            pool.setMaximumPoolSize(1); pool.setMinimumIdle(1); pool.setConnectionTimeout(300);
            var metadata = new MetadataRegistry(); var scanner = new MetadataScanner(new DefaultNamingStrategy());
            metadata.registerCatalog(scanner.scan(Conversation.class)); metadata.registerCatalog(scanner.scan(ConversationMessage.class));
            var springJdbi = Jdbi.create(new org.jdbi.v3.spring5.SpringConnectionFactory(pool));
            var feed = new CrmFeedQuery(springJdbi, repository, metadata,
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(pool));
            var nested = new org.springframework.jdbc.core.JdbcTemplate(pool);
            var result = feed.inbox("unreadCount", false, null, 1, "single-pool", false, (h, sink) -> {
                assertThat(nested.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
                sink.add(visible, 10);
            });
            assertThat(result.page().rows()).containsExactly(visible);
            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
            assertThat(nested.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME LIKE 'ONNO_CRM_PAGE_%'", Integer.class)).isZero();
        }
    }

    @Test void inboxUsesStableTypedKeysOptionalCount() {
        var values=new LinkedHashMap<UUID,Object>();for(int i=1;i<=6;i++)values.put(new UUID(0,i),i>4?null:10);
        var first=inbox(values,null,2,false,"alice");assertThat(first.total()).isNull();
        assertThat(first.page().rows()).containsExactly(new UUID(0,1),new UUID(0,2));
        values.put(new UUID(0,0),20);
        var rest=inbox(values,first.page().nextCursor(),100,true,"alice");
        assertThat(rest.page().rows()).containsExactly(new UUID(0,3),new UUID(0,4),new UUID(0,5),new UUID(0,6));
        assertThat(rest.page().nextCursor()).isNull();assertThat(rest.total()).isEqualTo(7);
        assertThat(inbox(values,"damaged",2,false,"alice").page().rows()).containsExactly(new UUID(0,0),new UUID(0,1));
        assertThatThrownBy(()->inbox(values,first.page().nextCursor(),2,false,"bob"))
                .isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(400));
    }
    private CrmFeedQuery.Window inbox(Map<UUID,Object> values,String cursor,int size,boolean count,String context) {
        return query.inbox("unreadCount",true,cursor,size,context,count,(h,sink)->values.forEach(sink::add));
    }
    @Test void activityUnionsSourcesBeforePagingAndNeverReadsHiddenConversationEntries() {
        var ids=new ArrayList<>();String cursor=null;
        do {
            var page=query.activity(cursor,2,"customer:alice",(h,sink)->sink.add(visible,null),row->row.get("_source")+":"+row.get("_id"));
            ids.addAll(page.rows());cursor=page.nextCursor();
        }while(cursor!=null);
        assertThat(ids).hasSize(6).doesNotHaveDuplicates();assertThat(ids.getFirst()).isEqualTo("note:"+new UUID(0,6));
        assertThat(ids).doesNotContain("message:"+new UUID(0,100));
        var first=query.activity(null,1,"customer:alice",(h,sink)->sink.add(visible,null),row->row.get("_id"));
        assertThatThrownBy(()->query.activity(first.nextCursor(),1,"customer:bob",(h,sink)->sink.add(visible,null),row->row))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(query.activity("damaged",1,"customer:alice",(h,sink)->sink.add(visible,null),row->row.get("_id")).rows())
                .containsExactly(new UUID(0,6));
    }
    @Test void sourceTraversalIsBatchedWithoutUsingTheUnboundedRepositoryMethod() {
        jdbi.useHandle(h->h.execute("INSERT INTO "+conversationTable+" SELECT RANDOM_UUID(),false FROM SYSTEM_RANGE(1,501)"));
        when(repository.findAllById(any())).thenAnswer(call->{
            List<Conversation> result=new ArrayList<>();
            Iterable<UUID> ids = call.getArgument(0);
            for(UUID id:ids){var c=new Conversation();c.setId(id);result.add(c);}return result;
        });
        List<Integer> sizes=new ArrayList<>();
        jdbi.useHandle(h->query.scan(h,batch->sizes.add(batch.size())));
        assertThat(sizes).containsExactly(500,1);verify(repository,never()).findAllActive();
    }
}
