package su.onno.posting;

import su.onno.annotations.Document;
import su.onno.lifecycle.BeforeUnpostHandler;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The before-unpost domain hook (#546): symmetric to {@code BeforePostHandler}, it can veto an unpost. */
class BeforeUnpostHandlerTest {

    @Document(name = "GuardedPayments", numberPrefix = "GP-")
    public static class GuardedPayment extends DocumentObject implements Postable, BeforeUnpostHandler {
        boolean locked;
        int unpostChecks;

        @Override
        public void handlePosting(PostingContext context) {
            // no movements: the hook is what is under test
        }

        @Override
        public void beforeUnpost() {
            unpostChecks++;
            if (locked) {
                throw new IllegalStateException("Payment is reconciled and cannot be unposted");
            }
        }
    }

    private Jdbi jdbi;
    private PostingEngine engine;
    private DocumentDescriptor descriptor;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbi = Jdbi.create(ds);
        MetadataRegistry registry = new MetadataRegistry();
        descriptor = new MetadataScanner(new DefaultNamingStrategy()).scanDocument(GuardedPayment.class);
        registry.registerDocument(descriptor);
        new SchemaGenerator(registry).execute(jdbi);
        engine = new PostingEngine(jdbi, registry, Map.of());
    }

    @Test
    void beforeUnpost_vetoKeepsDocumentPosted() {
        GuardedPayment payment = postedPayment();
        payment.locked = true;

        assertThatThrownBy(() -> engine.unpost(payment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reconciled");

        assertThat(payment.isPosted()).isTrue();
        assertThat(storedPosted(payment)).isTrue();
    }

    @Test
    void beforeUnpost_runsOnceThenUnposts() {
        GuardedPayment payment = postedPayment();

        engine.unpost(payment);

        assertThat(payment.unpostChecks).isEqualTo(1);
        assertThat(payment.isPosted()).isFalse();
        assertThat(storedPosted(payment)).isFalse();
    }

    @Test
    void repost_doesNotRunBeforeUnpost() {
        GuardedPayment payment = postedPayment();
        payment.locked = true;

        engine.repost(payment);

        assertThat(payment.unpostChecks).isZero();
        assertThat(storedPosted(payment)).isTrue();
    }

    private GuardedPayment postedPayment() {
        GuardedPayment payment = new GuardedPayment();
        payment.setId(UUID.randomUUID());
        payment.setNumber("GP-001");
        payment.setDate(LocalDateTime.of(2026, 3, 15, 10, 0));
        jdbi.useHandle(h -> h.createUpdate(
                        "INSERT INTO " + descriptor.tableName()
                                + " (_id, _number, _date, _posted, _deletion_mark) "
                                + "VALUES (:id, :number, :date, FALSE, FALSE)")
                .bind("id", payment.getId())
                .bind("number", payment.getNumber())
                .bind("date", payment.getDate())
                .execute());
        engine.post(payment);
        return payment;
    }

    private boolean storedPosted(GuardedPayment payment) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT _posted FROM " + descriptor.tableName() + " WHERE _id = :id")
                .bind("id", payment.getId())
                .mapTo(Boolean.class)
                .one());
    }
}
