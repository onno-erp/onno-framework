package su.onno.process;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import su.onno.metadata.MetadataRegistry;
import su.onno.schema.SchemaGenerator;
import su.onno.query.Cursor;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ProcessPaginationTest {
    enum Step implements ProcessStepKey { REVIEW, DONE }
    enum Outcome { OK }
    record Payload(String role) {}
    static class Definition extends ProcessDefinition<Payload, Step> {
        Definition() { super("paging", Payload.class); }
        public TaskAssignment startAssignment(Payload payload) { return TaskAssignment.actors(ProcessActorId.of("starter")); }
        protected void define(ProcessGraph<Payload, Step> graph) {
            var task = graph.human(Step.REVIEW, new HumanTask<Payload, Outcome>() {
                public Class<Outcome> outcomeType() { return Outcome.class; }
                public TaskAssignment assignment(Payload p) { return TaskAssignment.roles(p.role()); }
            });
            graph.start().to(task);
            task.on(Outcome.OK).to(graph.end(Step.DONE));
        }
    }
    static class Codec implements ProcessPayloadCodec {
        final Map<String, Object> values = new HashMap<>();
        public String write(Object value) {
            String key = UUID.randomUUID().toString(); values.put(key, value); return key;
        }
        public <T> T read(String key, Class<T> type) {
            Object value = values.get(key);
            if (type == String[].class && value instanceof Collection<?> items)
                return type.cast(items.stream().map(Object::toString).toArray(String[]::new));
            return type.cast(value);
        }
    }
    @Test void tasksAndInstancesApplyAssignmentsBeforeSqlLimitAndSurviveBackfill() {
        var ds = new JdbcDataSource(); ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var jdbi = Jdbi.create(ds); var registry = new MetadataRegistry(); new SchemaGenerator(registry).execute(jdbi);
        var definition = new Definition(); var defs = new ProcessDefinitions(List.of(definition)); var codec = new Codec();
        var engine = new JdbcProcessEngine(jdbi, defs, registry, codec,
                Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC), event -> {});
        var starter = new ProcessActor("starter", Set.of());
        var reader = new ProcessActor("alice", Set.of("REVIEWER"));
        for (int i=0; i<5; i++) engine.start(definition, new Payload(i==2 ? "OTHER" : "REVIEWER"), starter);
        // Upgrade a database created by the preceding release, retaining live work and its JSON.
        jdbi.useHandle(h -> {
            h.execute("DROP TABLE onno_process_work_candidates");
            h.execute("DROP INDEX IF EXISTS idx_onno_process_work_items__candidates_indexed__id");
            h.execute("ALTER TABLE onno_process_work_items DROP COLUMN _candidates_indexed");
        });
        new su.onno.schema.SchemaUpgrader(registry, su.onno.schema.SchemaMode.APPLY, false).run(jdbi);
        List<UUID> tasks = new ArrayList<>(); String cursor = null;
        do { var page=engine.inbox(reader,cursor,1); page.rows().forEach(r -> tasks.add(r.id())); cursor=page.nextCursor(); }
        while(cursor!=null);
        assertThat(tasks).hasSize(4).doesNotHaveDuplicates();
        // A preceding-version node can still create unindexed assignments during rolling upgrade.
        jdbi.useHandle(h -> { h.execute("DELETE FROM onno_process_work_candidates");
            h.execute("UPDATE onno_process_work_items SET _candidates_indexed = false"); });
        assertThat(engine.inbox(reader, null, 100).rows()).hasSize(4);
        List<UUID> instances = new ArrayList<>();
        do { var page=engine.instances(reader,cursor,1); page.rows().forEach(r -> instances.add(r.id())); cursor=page.nextCursor(); }
        while(cursor!=null);
        assertThat(instances).hasSize(4).doesNotHaveDuplicates();
        assertThat(engine.canAccess(instances.getLast(), reader)).isTrue();
        assertThat(engine.instances(new ProcessActor("stranger", Set.of()),null,100).rows()).isEmpty();
        String token=engine.inbox(reader,null,1).nextCursor();
        assertThatThrownBy(() -> engine.inbox(new ProcessActor("bob",Set.of("REVIEWER")),token,1))
                .isInstanceOf(Cursor.ScopeMismatchException.class);
        assertThat(engine.inbox(reader,"damaged",1).rows()).hasSize(1);
        var claimed=engine.claim(tasks.getFirst(), reader);
        assertThat(engine.inbox(new ProcessActor("bob",Set.of("REVIEWER")),null,100).rows())
                .noneMatch(row -> row.id().equals(claimed.id()));
        var history=engine.workItemHistory(claimed.id(),reader,null,1);
        assertThat(history.hasMore()).isTrue();
        assertThat(engine.workItemHistory(claimed.id(),reader,history.nextCursor(),1).rows()).hasSize(1);
        var processHistory=engine.history(claimed.instanceId(),null,1);
        assertThat(processHistory.rows()).hasSize(1);
        assertThat(engine.tokens(claimed.instanceId(),null,1).rows()).hasSize(1);
    }
}
