package su.onno.posting;

import su.onno.annotations.Document;
import su.onno.history.HistoryActor;
import su.onno.history.HistoryActors;
import su.onno.history.HistoryEntry;
import su.onno.history.RecordHistory;
import su.onno.lifecycle.Postable;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.DocumentObject;
import su.onno.schema.SchemaGenerator;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Post/unpost of a document with {@code @Document(history = true)} is recorded by the posting engine
 * inside the posting transaction (#422): every caller of {@code PostingService} is covered, and a
 * post that fails records nothing.
 */
class PostingHistoryTest {

    @Document(name = "HistoryPostedVisits", numberPrefix = "HV-", history = true)
    public static class HistoryPostedVisit extends DocumentObject implements Postable {
        @Override
        public void handlePosting(PostingContext context) {
            // no movements: only the posting state transition is under test
        }
    }

    private Jdbi jdbi;
    private DocumentDescriptor descriptor;
    private RecordHistory history;
    private PostingEngine engine;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbi = Jdbi.create(ds);
        MetadataRegistry registry = new MetadataRegistry();
        descriptor = new MetadataScanner(new DefaultNamingStrategy()).scanDocument(HistoryPostedVisit.class);
        registry.registerDocument(descriptor);
        new SchemaGenerator(registry).execute(jdbi);
        history = new RecordHistory(jdbi, registry, null, null);
        engine = new PostingEngine(jdbi, registry, Map.of());
        engine.setHistory(history);
    }

    @Test
    void postAndUnpostAreRecordedWithTheActor() {
        HistoryPostedVisit visit = storedVisit();

        HistoryActors.runAs(HistoryActor.named("vet@clinic.test"), () -> engine.post(visit));
        engine.repost(visit);
        engine.unpost(visit);

        List<HistoryEntry> entries = history.list(visit.getId(), null, 10);
        assertThat(entries).extracting(HistoryEntry::event).containsExactly(
                RecordHistory.UNPOSTED, RecordHistory.POSTED, RecordHistory.POSTED);
        assertThat(entries).extracting(HistoryEntry::actor)
                .containsExactly("system", "system", "vet@clinic.test");
        assertThat(entries.get(0).changes()).isEmpty();
    }

    @Test
    void aRejectedPostRecordsNothing() {
        HistoryPostedVisit visit = storedVisit();
        engine.post(visit);

        assertThatThrownBy(() -> engine.post(visit)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> {
            engine.unpost(visit);
            engine.unpost(visit);
        }).isInstanceOf(IllegalStateException.class);

        assertThat(history.list(visit.getId(), null, 10)).extracting(HistoryEntry::event)
                .containsExactly(RecordHistory.UNPOSTED, RecordHistory.POSTED);
    }

    private HistoryPostedVisit storedVisit() {
        HistoryPostedVisit visit = new HistoryPostedVisit();
        visit.setId(UUID.randomUUID());
        visit.setNumber("HV-001");
        visit.setDate(LocalDateTime.of(2026, 11, 16, 10, 0));
        jdbi.useHandle(h -> h.createUpdate(
                        "INSERT INTO " + descriptor.tableName()
                                + " (_id, _number, _date, _posted, _deletion_mark) "
                                + "VALUES (:id, :number, :date, FALSE, FALSE)")
                .bind("id", visit.getId())
                .bind("number", visit.getNumber())
                .bind("date", visit.getDate())
                .execute());
        return visit;
    }
}
