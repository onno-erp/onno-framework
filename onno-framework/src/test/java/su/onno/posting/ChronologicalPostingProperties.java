package su.onno.posting;

import su.onno.fixtures.TestCostIssue;
import su.onno.fixtures.TestCostReceipt;
import su.onno.fixtures.TestCostRegister;
import su.onno.model.DocumentObject;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code postingOrder = CHRONOLOGICAL}: a moving-average cost register (the {@code TestCost*}
 * fixtures) must end in the same state whatever order the documents are posted in. The reference
 * run posts a random valid document set in date order; the property run posts an identical copy
 * (another product, same relative dates) in a random order, retrying documents the balance check
 * rejects until everything is posted. Totals, every issue's computed cost and every document's
 * active movements must match.
 */
@Tag("property")
class ChronologicalPostingProperties {

    private static final PostingHarness HARNESS = new PostingHarness();
    /**
     * Each try posts into its own date window, later than every earlier try's, so the restoration
     * of one try never has to repost another try's (later-dated) documents.
     */
    private static final AtomicInteger WINDOWS = new AtomicInteger();

    /** A receipt (quantity + amount) or an issue (quantity; cost computed at posting). */
    record Spec(boolean issue, int quantity, int amountCents, int day) {}

    record Scenario(List<Spec> documents, List<Integer> postingOrder) {}

    @Property(tries = 100)
    @Label("CHRONOLOGICAL register: any posting order == date-order posting (totals, costs, movements)")
    void anyPostingOrderEqualsDateOrder(@ForAll("scenarios") Scenario scenario) {
        assertOrderIndependent(scenario);
    }

    /**
     * Same property when several documents share one timestamp. DISABLED: it fails today. The restoration orders documents
     * by (date, id) ({@code PostingEngine.documentOrder}) but only reverses documents dated
     * <em>strictly</em> after the requested one, so an equal-timestamp document posted earlier is
     * never re-sequenced behind a lower-id one posted later. Minimal counterexample (ids ascend
     * with the list index, so date order is R1, I, R2):
     * <pre>
     *   R1 day 0: +10 @ 100.00    I day 1: -5 (cost?)    R2 day 1: +10 @ 300.00
     *   date order R1, I, R2  -> I costs  50.00, balance 15 @ 350.00
     *   posted     R1, R2, I  -> I costs 100.00, balance 15 @ 300.00
     * </pre>
     * jqwik's own shrunk sample (two receipts on day 0, two issues on day 1, posted issues first)
     * ends with an issue cost of 1.00 instead of the date-order 1.01.
     */
    @Property(tries = 100)
    @Label("CHRONOLOGICAL register with equal timestamps: posting order still does not matter")
    @net.jqwik.api.Disabled("bug: equal-timestamp documents are not re-sequenced by id; restoration "
            + "selects _period > date only. Fix needs a (date, id) seek consistent with documentOrder() "
            + "and a decision on the intended tie semantics; see the method Javadoc.")
    void equalTimestampsAreSequencedById(@ForAll("tiedScenarios") Scenario scenario) {
        assertOrderIndependent(scenario);
    }

    private void assertOrderIndependent(Scenario scenario) {
        List<Spec> specs = scenario.documents();

        Run reference = new Run(specs);
        List<Integer> dateOrder = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) dateOrder.add(i);
        // Ties on the date fall back to the id, which ascends with the list index (see Run).
        dateOrder.sort(Comparator.<Integer>comparingInt(i -> specs.get(i).day()).thenComparing(i -> i));
        for (int index : dateOrder) {
            boolean posted = reference.tryPost(index);
            Assume.that(posted); // only scenarios that are valid in date order
        }

        Run shuffled = new Run(specs);
        List<Integer> pending = new ArrayList<>(scenario.postingOrder());
        while (!pending.isEmpty()) {
            List<Integer> rejected = new ArrayList<>();
            for (int index : pending) {
                if (!shuffled.tryPost(index)) rejected.add(index);
            }
            assertThat(rejected).as("some document can always be posted; stuck with %s", rejected)
                    .hasSizeLessThan(pending.size());
            pending = rejected;
        }

        assertThat(shuffled.totals()).as("final totals").isEqualTo(reference.totals());
        for (int i = 0; i < specs.size(); i++) {
            if (specs.get(i).issue()) {
                assertThat(shuffled.postedCost(i)).as("posted cost of issue #%s (day %s)", i, specs.get(i).day())
                        .isEqualByComparingTo(reference.postedCost(i));
            }
            assertThat(shuffled.activeMovements(i)).as("active movements of #%s", i)
                    .isEqualTo(reference.activeMovements(i));
        }
    }

    /** One posting run: its own product and date window, one document per spec. */
    private static final class Run {
        final UUID product = UUID.randomUUID();
        final int window = WINDOWS.incrementAndGet();
        final LocalDateTime base = LocalDateTime.of(2000, 1, 1, 12, 0).plusDays(100L * window);
        final List<DocumentObject> documents = new ArrayList<>();

        Run(List<Spec> specs) {
            for (int i = 0; i < specs.size(); i++) {
                Spec spec = specs.get(i);
                LocalDateTime date = base.plusDays(spec.day());
                if (spec.issue()) {
                    TestCostIssue issue = new TestCostIssue();
                    issue.setId(id(i));
                    issue.setNumber("CI-" + i);
                    issue.setDate(date);
                    issue.setProduct(product);
                    issue.setQuantity(BigDecimal.valueOf(spec.quantity()));
                    HARNESS.insert(issue, Map.of("product", product, "quantity", issue.getQuantity()));
                    documents.add(issue);
                } else {
                    TestCostReceipt receipt = new TestCostReceipt();
                    receipt.setId(id(i));
                    receipt.setNumber("CR-" + i);
                    receipt.setDate(date);
                    receipt.setProduct(product);
                    receipt.setQuantity(BigDecimal.valueOf(spec.quantity()));
                    receipt.setAmount(BigDecimal.valueOf(spec.amountCents(), 2));
                    HARNESS.insert(receipt, Map.of("product", product, "quantity", receipt.getQuantity(),
                            "amount", receipt.getAmount()));
                    documents.add(receipt);
                }
            }
        }

        /** Document ids ascend with the list index (positive halves: Java and SQL agree on the order). */
        private UUID id(int index) {
            return new UUID(window, index + 1L);
        }

        boolean tryPost(int index) {
            try {
                HARNESS.engine.post(documents.get(index));
                return true;
            } catch (IllegalStateException rejected) {
                // negative balance, or "No stock" from the fixture: a legitimate business rejection
                return false;
            }
        }

        List<BigDecimal> totals() {
            return HARNESS.totals(TestCostRegister.class, List.of("product"), List.of("quantity", "amount"),
                    "product = :product", Map.of("product", product)).getOrDefault(List.of(product), List.of());
        }

        BigDecimal postedCost(int index) {
            // Posting calculations belong to the register; the document header is not written.
            return HARNESS.cost.getRecordsByDocument(documents.get(index).getId()).stream()
                    .filter(TestCostRegister::isActive)
                    .map(TestCostRegister::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        List<String> activeMovements(int index) {
            return HARNESS.cost.getRecordsByDocument(documents.get(index).getId()).stream()
                    .filter(TestCostRegister::isActive)
                    .map(r -> r.getMovementType() + " " + r.getQuantity().stripTrailingZeros().toPlainString()
                            + " " + r.getAmount().stripTrailingZeros().toPlainString()
                            + " @" + java.time.Duration.between(base, r.getPeriod()).toDays())
                    .sorted()
                    .toList();
        }
    }

    // ------------------------------------------------------------------ generators

    @Provide
    Arbitrary<Scenario> scenarios() {
        return scenarios(false);
    }

    @Provide
    Arbitrary<Scenario> tiedScenarios() {
        return scenarios(true);
    }

    private static Arbitrary<Scenario> scenarios(boolean ties) {
        // Raw draws, then shaped into a sequence that is valid in date order: an issue never
        // takes more than the stock on hand at its date (an empty-stock issue becomes a receipt).
        Arbitrary<List<int[]>> raw = Combinators.combine(
                        Arbitraries.of(true, false), Arbitraries.integers().between(1, 10),
                        Arbitraries.integers().between(100, 100_000), Arbitraries.integers().between(1, 100))
                .as((issue, quantity, cents, share) -> new int[]{issue ? 1 : 0, quantity, cents, share})
                .list().ofMinSize(1).ofMaxSize(7);
        return raw.flatMap(draws -> {
            List<Spec> specs = new ArrayList<>();
            int onHand = 0;
            for (int day = 0; day < draws.size(); day++) {
                int[] d = draws.get(day);
                if (d[0] == 1 && onHand > 0) {
                    int quantity = Math.max(1, onHand * d[3] / 100);
                    specs.add(new Spec(true, quantity, 0, ties ? day / 2 : day));
                    onHand -= quantity;
                } else {
                    specs.add(new Spec(false, d[1], d[2], ties ? day / 2 : day));
                    onHand += d[1];
                }
            }
            // Present them in a random list order so "index" carries no date information,
            // and draw an independent random posting order.
            return Arbitraries.shuffle(specs).flatMap(listed -> {
                List<Integer> indexes = new ArrayList<>();
                for (int i = 0; i < listed.size(); i++) indexes.add(i);
                return Arbitraries.shuffle(indexes).map(order -> new Scenario(listed, order));
            });
        });
    }
}
