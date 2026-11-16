package su.onno.period;

import su.onno.annotations.AccumulationRegister;
import su.onno.annotations.Attribute;
import su.onno.annotations.Dimension;
import su.onno.annotations.Document;
import su.onno.annotations.Resource;
import su.onno.lifecycle.Postable;
import su.onno.metadata.DefaultNamingStrategy;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.MetadataScanner;
import su.onno.model.AccumulationRecord;
import su.onno.model.AccumulationType;
import su.onno.model.DocumentObject;
import su.onno.model.PostingOrder;
import su.onno.posting.PostingContext;
import su.onno.posting.PostingEngine;
import su.onno.posting.RegisterPersistence;
import su.onno.repository.RegisterRepositoryImpl;
import su.onno.schema.SchemaGenerator;
import su.onno.types.Ref;

import lombok.Getter;
import lombok.Setter;
import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PeriodCloseTest {

    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final LocalDate JANUARY_END = LocalDate.of(2026, 1, 31);

    private Jdbi jdbi;
    private MetadataRegistry registry;
    private PeriodCloseService periods;
    private PostingEngine engine;
    private final Map<UUID, DocumentObject> persisted = new HashMap<>();

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbi = Jdbi.create(ds);

        registry = new MetadataRegistry();
        MetadataScanner scanner = new MetadataScanner(new DefaultNamingStrategy());
        registry.registerDocument(scanner.scanDocument(PcReceipt.class));
        registry.registerDocument(scanner.scanDocument(PcIssue.class));
        registry.registerDocument(scanner.scanDocument(PcPayment.class));
        registry.registerDocument(scanner.scanDocument(PcAdjustment.class));
        registry.registerDocument(scanner.scanDocument(PcBranchPayment.class));
        registry.registerAccumulation(scanner.scanRegister(PcCost.class));
        registry.registerAccumulation(scanner.scanRegister(PcCash.class));
        new SchemaGenerator(registry).execute(jdbi);

        Map<Class<?>, RegisterRepositoryImpl<?>> repositories = new HashMap<>();
        repositories.put(PcCost.class, new RegisterRepositoryImpl<>(
                new RegisterPersistence<>(jdbi, registry.getRegisterDescriptor(PcCost.class)), PcCost.class));
        repositories.put(PcCash.class, new RegisterRepositoryImpl<>(
                new RegisterPersistence<>(jdbi, registry.getRegisterDescriptor(PcCash.class)), PcCash.class));

        periods = new PeriodCloseService(jdbi, registry);
        periods.ensureSchema();
        engine = new PostingEngine(jdbi, registry, repositories, null, null,
                ids -> ids.stream().map(persisted::get).filter(Objects::nonNull).toList());
        engine.setPeriodCloseService(periods);
    }

    @Test
    void globalClose_rejectsPostOnTheBoundaryDay_andAllowsTheNextDay() {
        periods.close(PeriodScope.global(), JANUARY_END, "admin", "month end");

        PcPayment lastClosedMoment = payment(LocalDateTime.of(2026, 1, 31, 23, 59, 59), "10");
        assertThatThrownBy(() -> engine.post(lastClosedMoment))
                .isInstanceOf(PeriodClosedException.class)
                .hasMessageContaining("closed through 2026-01-31")
                .hasMessageContaining("all documents")
                .hasMessageContaining("cannot post PcPayments dated 2026-01-31")
                .hasMessageContaining("reopen the period");
        assertThat(storedPosted(lastClosedMoment)).isFalse();
        assertThat(movements("register_pc_cash")).isZero();

        PcPayment firstOpenMoment = payment(LocalDateTime.of(2026, 2, 1, 0, 0), "10");
        engine.post(firstOpenMoment);
        assertThat(storedPosted(firstOpenMoment)).isTrue();
    }

    @Test
    void close_rejectsUnpostAndRepost_leavingThePostingIntact() {
        PcPayment payment = payment(LocalDateTime.of(2026, 1, 15, 10, 0), "10");
        engine.post(payment);
        periods.close(PeriodScope.global(), JANUARY_END, "admin", null);

        assertThatThrownBy(() -> engine.unpost(payment))
                .isInstanceOf(PeriodClosedException.class)
                .hasMessageContaining("cannot unpost");
        assertThatThrownBy(() -> engine.repost(payment))
                .isInstanceOf(PeriodClosedException.class)
                .hasMessageContaining("cannot repost");
        assertThat(storedPosted(payment)).isTrue();
        assertThat(movements("register_pc_cash")).isEqualTo(1);
    }

    @Test
    void unpost_usesTheStoredDate_notTheDateOnTheInstance() {
        PcPayment payment = payment(LocalDateTime.of(2026, 1, 15, 10, 0), "10");
        engine.post(payment);
        periods.close(PeriodScope.global(), JANUARY_END, "admin", null);

        payment.setDate(LocalDateTime.of(2026, 3, 1, 10, 0));
        assertThatThrownBy(() -> engine.unpost(payment)).isInstanceOf(PeriodClosedException.class);
        assertThatThrownBy(() -> engine.repost(payment)).isInstanceOf(PeriodClosedException.class);
    }

    @Test
    void contextClose_appliesOnlyToThatContext() {
        periods.close(PeriodScope.context("Finance"), JANUARY_END, "admin", null);

        engine.post(receipt(LocalDateTime.of(2026, 1, 10, 10, 0), "10", "100"));

        assertThatThrownBy(() -> engine.post(payment(LocalDateTime.of(2026, 1, 10, 10, 0), "10")))
                .isInstanceOf(PeriodClosedException.class)
                .hasMessageContaining("context Finance");
    }

    @Test
    void registerClose_appliesToDocumentsMovingThatRegister() {
        periods.close(PeriodScope.register("PcCash"), JANUARY_END, "admin", null);

        engine.post(receipt(LocalDateTime.of(2026, 1, 10, 10, 0), "10", "100"));

        assertThatThrownBy(() -> engine.post(payment(LocalDateTime.of(2026, 1, 10, 10, 0), "10")))
                .isInstanceOf(PeriodClosedException.class)
                .hasMessageContaining("register PcCash");
    }

    @Test
    void registerClose_guardsUnpostOfAlreadyWrittenMovements() {
        PcPayment payment = payment(LocalDateTime.of(2026, 1, 10, 10, 0), "10");
        engine.post(payment);
        periods.close(PeriodScope.register("PcCash"), JANUARY_END, "admin", null);

        assertThatThrownBy(() -> engine.unpost(payment)).isInstanceOf(PeriodClosedException.class);
    }

    @Test
    void theLatestApplicableCloseWins() {
        periods.close(PeriodScope.global(), LocalDate.of(2025, 12, 31), "admin", null);
        periods.close(PeriodScope.context("Finance"), JANUARY_END, "admin", null);

        assertThatThrownBy(() -> engine.post(payment(LocalDateTime.of(2026, 1, 10, 10, 0), "10")))
                .isInstanceOf(PeriodClosedException.class)
                .hasMessageContaining("closed through 2026-01-31");
    }

    @Test
    void chronologicalRestoration_doesNotCascadeIntoAClosedPeriod() {
        UUID product = UUID.randomUUID();
        PcReceipt opening = receipt(LocalDateTime.of(2026, 1, 1, 10, 0), product, "10", "100");
        engine.post(opening);
        PcIssue issue = issue(LocalDateTime.of(2026, 1, 3, 10, 0), product, "5");
        engine.post(issue);
        persisted.put(issue.getId(), issue);
        assertThat(issue.getCost()).isEqualByComparingTo("50.00");

        // Finance (the issue's context) is closed; Inventory (the receipt's) stays open.
        periods.close(PeriodScope.context("Finance"), JANUARY_END, "admin", null);

        PcReceipt backdated = receipt(LocalDateTime.of(2026, 1, 2, 10, 0), product, "10", "300");
        assertThatThrownBy(() -> engine.post(backdated))
                .isInstanceOf(PeriodClosedException.class)
                .hasMessageContaining("cannot recalculate PcIssues dated 2026-01-03");

        assertThat(storedPosted(backdated)).isFalse();
        assertThat(movements("register_pc_cost")).isEqualTo(2);
        BigDecimal storedCost = jdbi.withHandle(h -> h.createQuery(
                        "SELECT cost FROM document_pc_issues WHERE _id = :id")
                .bind("id", issue.getId()).mapTo(BigDecimal.class).one());
        assertThat(storedCost).isEqualByComparingTo("50.00");

        // Unposting the opening receipt would recalculate the closed issue as well.
        assertThatThrownBy(() -> engine.unpost(opening)).isInstanceOf(PeriodClosedException.class);
        assertThat(storedPosted(opening)).isTrue();

        // A later, open document posts without touching the closed one.
        engine.post(receipt(LocalDateTime.of(2026, 2, 5, 10, 0), product, "1", "10"));
    }

    @Test
    void reopen_requiresAReason_movesTheDateBack_andIsAudited() {
        periods.close(PeriodScope.global(), JANUARY_END, "admin", "month end");
        PcPayment payment = payment(LocalDateTime.of(2026, 1, 20, 10, 0), "10");

        assertThatThrownBy(() -> periods.reopen(PeriodScope.global(), LocalDate.of(2026, 1, 15), "admin", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a reason");
        assertThatThrownBy(() -> periods.reopen(PeriodScope.global(), LocalDate.of(2026, 2, 15), "admin", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> periods.close(PeriodScope.global(), LocalDate.of(2026, 1, 15), "admin", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reopen");

        Optional<PeriodLock> remaining =
                periods.reopen(PeriodScope.global(), LocalDate.of(2026, 1, 15), "chief", "late invoice");
        assertThat(remaining).map(PeriodLock::closedThrough).contains(LocalDate.of(2026, 1, 15));
        engine.post(payment);

        assertThat(periods.reopen(PeriodScope.global(), null, "chief", "audit correction")).isEmpty();
        assertThat(periods.locks()).isEmpty();
        assertThat(periods.closedThrough(PeriodScope.global())).isEmpty();

        List<PeriodCloseAuditEntry> audit = periods.audit(50);
        assertThat(audit).extracting(PeriodCloseAuditEntry::action).containsExactlyInAnyOrder(
                PeriodCloseAction.CLOSE, PeriodCloseAction.REOPEN, PeriodCloseAction.REOPEN);
        assertThat(audit).filteredOn(e -> e.action() == PeriodCloseAction.REOPEN)
                .extracting(PeriodCloseAuditEntry::reason)
                .containsExactlyInAnyOrder("late invoice", "audit correction");
        assertThat(audit).filteredOn(e -> "late invoice".equals(e.reason())).singleElement()
                .satisfies(entry -> {
                    assertThat(entry.actor()).isEqualTo("chief");
                    assertThat(entry.previous()).isEqualTo(JANUARY_END);
                    assertThat(entry.closedThrough()).isEqualTo(LocalDate.of(2026, 1, 15));
                });
    }

    @Test
    void override_letsTheChangeThrough_andAuditsIt() {
        periods.close(PeriodScope.global(), JANUARY_END, "admin", null);
        PcPayment payment = payment(LocalDateTime.of(2026, 1, 20, 10, 0), "10");

        periods.override(new PeriodOverride("chief", "bank correction"), () -> engine.post(payment));

        assertThat(storedPosted(payment)).isTrue();
        assertThat(periods.audit(10)).filteredOn(e -> e.action() == PeriodCloseAction.OVERRIDE)
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.actor()).isEqualTo("chief");
                    assertThat(entry.reason()).isEqualTo("bank correction");
                    assertThat(entry.operation()).isEqualTo(PeriodOperation.POST);
                    assertThat(entry.documentId()).isEqualTo(payment.getId());
                    assertThat(entry.documentType()).isEqualTo("PcPayments");
                });
        // The override ends with the block.
        assertThatThrownBy(() -> engine.unpost(payment)).isInstanceOf(PeriodClosedException.class);
    }

    @Test
    void overridePolicy_grantsTheCurrentCaller() {
        periods.close(PeriodScope.global(), JANUARY_END, "admin", null);
        periods.setOverridePolicy(() -> Optional.of(new PeriodOverride("accountant", null)));

        engine.post(payment(LocalDateTime.of(2026, 1, 20, 10, 0), "10"));

        assertThat(periods.audit(10)).filteredOn(e -> e.action() == PeriodCloseAction.OVERRIDE).hasSize(1);
    }

    @Test
    void preview_reportsTheClosedPeriod_withoutAuditingAnOverride() {
        periods.close(PeriodScope.global(), JANUARY_END, "admin", null);
        PcPayment payment = payment(LocalDateTime.of(2026, 1, 20, 10, 0), "10");

        assertThatThrownBy(() -> engine.preview(payment)).isInstanceOf(PeriodClosedException.class);

        periods.override(new PeriodOverride("chief", null), () -> engine.preview(payment));
        assertThat(periods.audit(10)).noneMatch(e -> e.action() == PeriodCloseAction.OVERRIDE);
    }

    @Test
    void documentWrites_cannotChangeOrEnterAClosedPeriod() {
        DocumentDescriptor descriptor = registry.getDocumentDescriptor(PcPayment.class);
        PcPayment closed = payment(LocalDateTime.of(2026, 1, 20, 10, 0), "10");
        PcPayment open = payment(LocalDateTime.of(2026, 2, 20, 10, 0), "10");
        periods.close(PeriodScope.global(), JANUARY_END, "admin", null);

        // Editing a closed document, even when the edit moves it out of the period.
        assertThatThrownBy(() -> periods.requireDocumentOpen(descriptor, closed.getId(),
                LocalDateTime.of(2026, 2, 1, 10, 0), PeriodOperation.UPDATE))
                .isInstanceOf(PeriodClosedException.class)
                .hasMessageContaining("cannot change");
        assertThatThrownBy(() -> periods.requireDocumentOpen(descriptor, closed.getId(), null,
                PeriodOperation.DELETE))
                .isInstanceOf(PeriodClosedException.class)
                .hasMessageContaining("cannot delete");
        // Moving an open document into the period, or creating one there.
        assertThatThrownBy(() -> periods.requireDocumentOpen(descriptor, open.getId(),
                LocalDateTime.of(2026, 1, 5, 10, 0), PeriodOperation.UPDATE))
                .isInstanceOf(PeriodClosedException.class);
        assertThatThrownBy(() -> periods.requireDocumentOpen(descriptor, UUID.randomUUID(),
                LocalDateTime.of(2026, 1, 5, 10, 0), PeriodOperation.CREATE))
                .isInstanceOf(PeriodClosedException.class)
                .hasMessageContaining("cannot create");

        periods.requireDocumentOpen(descriptor, open.getId(), null, PeriodOperation.UPDATE);
        periods.requireDocumentOpen(descriptor, UUID.randomUUID(),
                LocalDateTime.of(2026, 2, 1, 0, 0), PeriodOperation.CREATE);
    }

    @Test
    void close_rejectsUnknownScopes() {
        assertThatThrownBy(() -> periods.close(PeriodScope.register("Nope"), JANUARY_END, "admin", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> periods.close(PeriodScope.context("Nope"), JANUARY_END, "admin", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(periods.knownContexts()).containsExactly("Finance", "Inventory", "Treasury");
        assertThat(periods.knownRegisters()).containsExactly("PcCash", "PcCost");
        assertThat(PeriodScope.parse("context:Finance")).isEqualTo(PeriodScope.context("Finance"));
        assertThat(PeriodScope.parse(PeriodScope.register("PcCash").toString()))
                .isEqualTo(PeriodScope.register("PcCash"));
        assertThat(PeriodScope.parse("global")).isEqualTo(PeriodScope.global());
    }

    @Test
    void organizationClose_appliesOnlyToThatOrganization_andToMovesIntoIt() {
        UUID north = UUID.randomUUID();
        UUID south = UUID.randomUUID();
        periods.close(PeriodScope.organization(north), JANUARY_END, "admin", null);
        LocalDateTime january = LocalDateTime.of(2026, 1, 20, 10, 0);

        assertThatThrownBy(() -> engine.post(branchPayment(january, north)))
                .isInstanceOf(PeriodClosedException.class)
                .hasMessageContaining("organization " + north);
        PcBranchPayment southern = branchPayment(january, south);
        engine.post(southern);
        assertThat(storedPosted(southern)).isTrue();
        engine.post(payment(january, "10")); // documents without the field are not covered

        DocumentDescriptor descriptor = registry.getDocumentDescriptor(PcBranchPayment.class);
        // Moving a southern document into the closed organization is rejected; the stored
        // organization counts too, so a northern one cannot be moved out.
        PcBranchPayment draft = branchPayment(january, south);
        assertThatThrownBy(() -> periods.requireDocumentOpen(descriptor, draft.getId(), null,
                Ref.of(Object.class, north), PeriodOperation.UPDATE))
                .isInstanceOf(PeriodClosedException.class);
        PcBranchPayment northern = branchPayment(january, north);
        assertThatThrownBy(() -> periods.requireDocumentOpen(descriptor, northern.getId(), null,
                south, PeriodOperation.UPDATE))
                .isInstanceOf(PeriodClosedException.class);
        periods.requireDocumentOpen(descriptor, draft.getId(), null, south.toString(), PeriodOperation.UPDATE);

        assertThat(PeriodScope.parse("organization:" + north)).isEqualTo(PeriodScope.organization(north));
        assertThat(periods.organizationFieldName(descriptor)).isEqualTo("branch");
    }

    @Test
    void organizationClose_needsADocumentDeclaringTheField() {
        MetadataRegistry bare = new MetadataRegistry();
        bare.registerDocument(new MetadataScanner(new DefaultNamingStrategy()).scanDocument(PcPayment.class));
        PeriodCloseService service = new PeriodCloseService(jdbi, bare);
        assertThatThrownBy(() -> service.close(PeriodScope.organization(UUID.randomUUID()), JANUARY_END,
                "admin", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@PeriodCloseOrganization");
    }

    @Test
    void softClose_admitsAdjustmentDocuments_onlyThem_andAuditsEach() {
        periods.close(PeriodScope.global(), JANUARY_END, "admin", null);
        periods.setOverridePolicy(new PeriodCloseOverridePolicy() {
            @Override
            public Optional<PeriodOverride> current() {
                return Optional.empty();
            }

            @Override
            public String actor() {
                return "accountant";
            }
        });
        LocalDateTime january = LocalDateTime.of(2026, 1, 20, 10, 0);

        PcAdjustment adjustment = adjustment(january, "-5");
        engine.post(adjustment);
        engine.unpost(adjustment);
        periods.requireDocumentOpen(registry.getDocumentDescriptor(PcAdjustment.class), UUID.randomUUID(),
                january, PeriodOperation.CREATE);
        assertThatThrownBy(() -> engine.post(payment(january, "10"))).isInstanceOf(PeriodClosedException.class);

        assertThat(periods.audit(10)).filteredOn(e -> e.action() == PeriodCloseAction.ADJUSTMENT)
                .hasSize(3)
                .allSatisfy(entry -> {
                    assertThat(entry.actor()).isEqualTo("accountant");
                    assertThat(entry.documentType()).isEqualTo("PcAdjustments");
                })
                .extracting(PeriodCloseAuditEntry::operation)
                .containsExactlyInAnyOrder(PeriodOperation.POST, PeriodOperation.UNPOST, PeriodOperation.CREATE);
    }

    @Test
    void hardClose_admitsNeitherAdjustmentsNorOverrides() {
        periods.close(PeriodScope.context("Finance"), JANUARY_END, true, "admin", "audited");
        LocalDateTime january = LocalDateTime.of(2026, 1, 20, 10, 0);

        assertThatThrownBy(() -> engine.post(adjustment(january, "-5")))
                .isInstanceOf(PeriodClosedException.class)
                .hasMessageContaining("context Finance");
        PcPayment payment = payment(january, "10");
        assertThatThrownBy(() -> periods.override(new PeriodOverride("chief", "fix"), () -> engine.post(payment)))
                .isInstanceOf(PeriodClosedException.class);
        assertThat(storedPosted(payment)).isFalse();

        // A soft global close covering the same day does not soften the hard context close.
        periods.close(PeriodScope.global(), LocalDate.of(2026, 2, 28), "admin", null);
        assertThatThrownBy(() -> periods.override(new PeriodOverride("chief", "fix"), () -> engine.post(payment)))
                .isInstanceOf(PeriodClosedException.class)
                .hasMessageContaining("context Finance");
        assertThat(periods.locks()).filteredOn(PeriodLock::hard).singleElement()
                .satisfies(lock -> assertThat(lock.state()).isEqualTo("hard"));
        assertThat(periods.audit(10)).filteredOn(e -> e.action() == PeriodCloseAction.CLOSE)
                .anySatisfy(entry -> assertThat(entry.hard()).isTrue());
    }

    @Test
    void hardClose_turnsSoftOnlyThroughAReopen() {
        PeriodScope finance = PeriodScope.context("Finance");
        periods.close(finance, JANUARY_END, true, "admin", null);

        assertThatThrownBy(() -> periods.close(finance, JANUARY_END, "admin", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reopen");
        assertThatThrownBy(() -> periods.close(finance, LocalDate.of(2026, 2, 28), false, "admin", null))
                .isInstanceOf(IllegalArgumentException.class);
        // Hardening forward is a close.
        periods.close(finance, LocalDate.of(2026, 2, 28), true, "admin", null);

        assertThat(periods.reopen(finance, LocalDate.of(2026, 2, 28), "admin", "late invoices"))
                .hasValueSatisfying(lock -> assertThat(lock.hard()).isFalse());
        assertThatThrownBy(() -> periods.reopen(finance, LocalDate.of(2026, 2, 28), "admin", "again"))
                .isInstanceOf(IllegalArgumentException.class);
        engine.post(adjustment(LocalDateTime.of(2026, 2, 10, 10, 0), "1"));
    }

    @Test
    void concurrentCloses_ofOneScope_serialize() throws Exception {
        periods.close(PeriodScope.global(), LocalDate.of(2026, 1, 1), "admin", null);
        int threads = 8;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<Boolean>> results = new java.util.ArrayList<>();
        for (int i = 1; i <= threads; i++) {
            LocalDate date = LocalDate.of(2026, 1, 1).plusDays(i);
            results.add(pool.submit(() -> {
                start.await();
                try {
                    periods.close(PeriodScope.global(), date, "admin", null);
                    return true;
                } catch (IllegalArgumentException movedBack) {
                    return false; // a later date committed first
                }
            }));
        }
        start.countDown();
        int succeeded = 0;
        for (java.util.concurrent.Future<Boolean> result : results) {
            if (result.get(30, java.util.concurrent.TimeUnit.SECONDS)) succeeded++;
        }
        pool.shutdown();

        assertThat(periods.closedThrough(PeriodScope.global())).contains(LocalDate.of(2026, 1, 1).plusDays(threads));
        List<PeriodCloseAuditEntry> closes = periods.audit(100).stream()
                .filter(e -> e.action() == PeriodCloseAction.CLOSE).toList();
        assertThat(closes).hasSize(succeeded + 1);
        // Each audited close starts from the date the previous one left: no lost update.
        assertThat(closes.stream().filter(e -> e.previous() != null).map(PeriodCloseAuditEntry::previous))
                .doesNotHaveDuplicates();
    }

    // --- fixtures ---------------------------------------------------------------------------

    private PcAdjustment adjustment(LocalDateTime date, String amount) {
        PcAdjustment adjustment = new PcAdjustment();
        adjustment.setId(UUID.randomUUID());
        adjustment.setNumber("ADJ");
        adjustment.setDate(date);
        adjustment.setAmount(new BigDecimal(amount));
        insert(adjustment, "amount", adjustment.getAmount());
        persisted.put(adjustment.getId(), adjustment);
        return adjustment;
    }

    private PcBranchPayment branchPayment(LocalDateTime date, UUID branch) {
        PcBranchPayment payment = new PcBranchPayment();
        payment.setId(UUID.randomUUID());
        payment.setNumber("BPAY");
        payment.setDate(date);
        payment.setBranch(branch);
        payment.setAmount(BigDecimal.TEN);
        insert(payment, "amount", payment.getAmount());
        String table = registry.getDocumentDescriptor(PcBranchPayment.class).tableName();
        jdbi.useHandle(h -> h.createUpdate("UPDATE " + table + " SET branch = :branch WHERE _id = :id")
                .bind("branch", branch).bind("id", payment.getId()).execute());
        return payment;
    }

    private PcPayment payment(LocalDateTime date, String amount) {
        PcPayment payment = new PcPayment();
        payment.setId(UUID.randomUUID());
        payment.setNumber("PAY");
        payment.setDate(date);
        payment.setAmount(new BigDecimal(amount));
        insert(payment, "amount", payment.getAmount());
        return payment;
    }

    private PcReceipt receipt(LocalDateTime date, String quantity, String amount) {
        return receipt(date, UUID.randomUUID(), quantity, amount);
    }

    private PcReceipt receipt(LocalDateTime date, UUID product, String quantity, String amount) {
        PcReceipt receipt = new PcReceipt();
        receipt.setId(UUID.randomUUID());
        receipt.setNumber("REC");
        receipt.setDate(date);
        receipt.setProduct(product);
        receipt.setQuantity(new BigDecimal(quantity));
        receipt.setAmount(new BigDecimal(amount));
        insert(receipt, "amount", receipt.getAmount());
        return receipt;
    }

    private PcIssue issue(LocalDateTime date, UUID product, String quantity) {
        PcIssue issue = new PcIssue();
        issue.setId(UUID.randomUUID());
        issue.setNumber("ISS");
        issue.setDate(date);
        issue.setProduct(product);
        issue.setQuantity(new BigDecimal(quantity));
        insert(issue, "quantity", issue.getQuantity());
        return issue;
    }

    private void insert(DocumentObject document, String column, BigDecimal value) {
        String table = registry.getDocumentDescriptor(document.getClass()).tableName();
        jdbi.useHandle(h -> h.createUpdate("INSERT INTO " + table
                        + " (_id, _number, _date, _posted, _deletion_mark, " + column + ")"
                        + " VALUES (:id, :number, :date, FALSE, FALSE, :value)")
                .bind("id", document.getId())
                .bind("number", document.getNumber())
                .bind("date", document.getDate())
                .bind("value", value)
                .execute());
    }

    private boolean storedPosted(DocumentObject document) {
        String table = registry.getDocumentDescriptor(document.getClass()).tableName();
        return jdbi.withHandle(h -> h.createQuery("SELECT _posted FROM " + table + " WHERE _id = :id")
                .bind("id", document.getId()).mapTo(Boolean.class).one());
    }

    private int movements(String table) {
        return jdbi.withHandle(h -> h.createQuery("SELECT COUNT(*) FROM " + table + " WHERE _active = TRUE")
                .mapTo(Integer.class).one());
    }

    @Document(name = "PcReceipts", context = "Inventory")
    @Getter
    @Setter
    public static class PcReceipt extends DocumentObject implements Postable {
        @Attribute
        private UUID product;
        @Attribute(precision = 15, scale = 3)
        private BigDecimal quantity;
        @Attribute(precision = 15, scale = 2)
        private BigDecimal amount;

        @Override
        public void handlePosting(PostingContext context) {
            context.movements(PcCost.class).addReceipt(movement -> {
                movement.setProduct(product);
                movement.setQuantity(quantity);
                movement.setAmount(amount);
            });
        }
    }

    @Document(name = "PcIssues", context = "Finance")
    @Getter
    @Setter
    public static class PcIssue extends DocumentObject implements Postable {
        @Attribute
        private UUID product;
        @Attribute(precision = 15, scale = 3)
        private BigDecimal quantity;
        @Attribute(precision = 15, scale = 2)
        private BigDecimal cost;

        @Override
        public void handlePosting(PostingContext context) {
            var stock = context.movements(PcCost.class);
            PcCost balance = stock.getBalance(Map.of("product", product)).stream().findFirst().orElseThrow();
            cost = balance.getAmount().divide(balance.getQuantity(), 8, RoundingMode.HALF_UP)
                    .multiply(quantity).setScale(2, RoundingMode.HALF_UP);
            stock.addExpense(movement -> {
                movement.setProduct(product);
                movement.setQuantity(quantity);
                movement.setAmount(cost);
            });
        }
    }

    @Document(name = "PcPayments", context = "Finance")
    @Getter
    @Setter
    public static class PcPayment extends DocumentObject implements Postable {
        @Attribute(precision = 15, scale = 2)
        private BigDecimal amount;

        @Override
        public void handlePosting(PostingContext context) {
            context.movements(PcCash.class).addReceipt(movement -> {
                movement.setAccount(ACCOUNT);
                movement.setAmount(amount);
            });
        }
    }

    @Document(name = "PcAdjustments", context = "Finance")
    @PeriodAdjustment
    @Getter
    @Setter
    public static class PcAdjustment extends DocumentObject implements Postable {
        @Attribute(precision = 15, scale = 2)
        private BigDecimal amount;

        @Override
        public void handlePosting(PostingContext context) {
            context.movements(PcCash.class).addReceipt(movement -> {
                movement.setAccount(ACCOUNT);
                movement.setAmount(amount);
            });
        }
    }

    @Document(name = "PcBranchPayments", context = "Treasury")
    @Getter
    @Setter
    public static class PcBranchPayment extends DocumentObject implements Postable {
        @PeriodCloseOrganization
        @Attribute
        private UUID branch;
        @Attribute(precision = 15, scale = 2)
        private BigDecimal amount;

        @Override
        public void handlePosting(PostingContext context) {
            context.movements(PcCash.class).addReceipt(movement -> {
                movement.setAccount(ACCOUNT);
                movement.setAmount(amount);
            });
        }
    }

    @AccumulationRegister(name = "PcCost", type = AccumulationType.BALANCE,
            postingOrder = PostingOrder.CHRONOLOGICAL, context = "Inventory")
    @Getter
    @Setter
    public static class PcCost extends AccumulationRecord {
        @Dimension
        private UUID product;
        @Resource(precision = 15, scale = 3)
        private BigDecimal quantity;
        @Resource(precision = 15, scale = 2)
        private BigDecimal amount;
    }

    @AccumulationRegister(name = "PcCash", type = AccumulationType.BALANCE, allowNegative = true,
            context = "Finance")
    @Getter
    @Setter
    public static class PcCash extends AccumulationRecord {
        @Dimension
        private UUID account;
        @Resource(precision = 15, scale = 2)
        private BigDecimal amount;
    }
}
