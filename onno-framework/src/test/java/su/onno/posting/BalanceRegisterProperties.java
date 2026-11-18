package su.onno.posting;

import su.onno.fixtures.TestReceipt;
import su.onno.fixtures.TestReceiptLine;
import su.onno.fixtures.TestStockIssue;
import su.onno.fixtures.TestStockIssueLine;
import su.onno.fixtures.TestStockRegister;
import su.onno.model.DocumentObject;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Properties of a BALANCE accumulation register without {@code allowNegative}, checked against a
 * simple in-memory model over arbitrary interleavings of receipts, issues and unposts on random
 * dimension tuples (product x warehouse):
 *
 * <ul>
 *   <li>a post/unpost is accepted iff the model says no touched tuple goes negative;</li>
 *   <li>a rejected operation leaves totals, movements and the {@code _posted} flag unchanged;</li>
 *   <li>after every step the stored totals equal the model AND the sum of active movements,
 *       and no tuple is negative;</li>
 *   <li>unposting everything restores every total to exactly zero.</li>
 * </ul>
 */
@Tag("property")
class BalanceRegisterProperties {

    private static final PostingHarness HARNESS = new PostingHarness();
    private static final List<String> DIMENSIONS = List.of("product", "warehouse");
    private static final List<String> RESOURCES = List.of("quantity");

    /** One document line: indexes into the try's product/warehouse pools and a positive quantity. */
    record Line(int product, int warehouse, int quantity) {}

    /** {@code unpost >= 0}: unpost the n-th (mod count) currently posted document; otherwise post a new one. */
    record Step(boolean issue, List<Line> lines, int unpost) {}

    @Property(tries = 120)
    @Label("BALANCE register: totals stay non-negative, match the model and the movements; rejects change nothing")
    void balanceRegisterInvariants(@ForAll("scenarios") List<Step> steps) {
        List<UUID> products = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        List<UUID> warehouses = List.of(UUID.randomUUID(), UUID.randomUUID());
        Map<List<Object>, BigDecimal> model = new HashMap<>();
        List<DocumentObject> posted = new ArrayList<>();
        Map<UUID, Map<List<Object>, BigDecimal>> deltas = new HashMap<>();
        int number = 0;

        for (Step step : steps) {
            if (step.unpost() >= 0 && posted.isEmpty()) continue;
            if (step.unpost() >= 0) {
                DocumentObject document = posted.get(step.unpost() % posted.size());
                Map<List<Object>, BigDecimal> delta = negate(deltas.get(document.getId()));
                boolean allowed = staysNonNegative(model, delta);
                Map<List<Object>, List<BigDecimal>> before = totals(products);
                Throwable failure = catchThrowable(() -> HARNESS.engine.unpost(document));
                if (allowed) {
                    assertThat(failure).as("unpost of %s should be accepted", document.getNumber()).isNull();
                    apply(model, delta);
                    posted.remove(document);
                    assertThat(HARNESS.isPosted(document)).isFalse();
                } else {
                    assertRejectedUnchanged(failure, before, products);
                    assertThat(HARNESS.isPosted(document)).isTrue();
                }
            } else {
                DocumentObject document = step.issue()
                        ? issue(++number, warehouses.get(step.lines().getFirst().warehouse()), step.lines(), products)
                        : receipt(++number, warehouses.get(step.lines().getFirst().warehouse()), step.lines(), products);
                Map<List<Object>, BigDecimal> delta = new HashMap<>();
                UUID warehouse = warehouses.get(step.lines().getFirst().warehouse());
                for (Line line : step.lines()) {
                    BigDecimal signed = BigDecimal.valueOf(step.issue() ? -line.quantity() : line.quantity());
                    delta.merge(List.of(products.get(line.product()), warehouse), signed, BigDecimal::add);
                }
                boolean allowed = staysNonNegative(model, delta);
                Map<List<Object>, List<BigDecimal>> before = totals(products);
                Throwable failure = catchThrowable(() -> HARNESS.engine.post(document));
                if (allowed) {
                    assertThat(failure).as("post of %s should be accepted", document.getNumber()).isNull();
                    apply(model, delta);
                    posted.add(document);
                    deltas.put(document.getId(), delta);
                    assertThat(HARNESS.isPosted(document)).isTrue();
                } else {
                    assertRejectedUnchanged(failure, before, products);
                    assertThat(HARNESS.isPosted(document)).isFalse();
                    assertThat(HARNESS.stock.getRecordsByDocument(document.getId())).isEmpty();
                }
            }
            assertConsistent(model, products);
        }

        // Unpost everything: issues first (only increases), then receipts (back down to zero).
        posted.sort((a, b) -> Boolean.compare(a instanceof TestReceipt, b instanceof TestReceipt));
        for (DocumentObject document : posted) {
            HARNESS.engine.unpost(document);
            apply(model, negate(deltas.get(document.getId())));
            assertConsistent(model, products);
        }
        assertThat(totals(products).values())
                .allSatisfy(v -> assertThat(v.getFirst()).isEqualByComparingTo(BigDecimal.ZERO));
    }

    // ------------------------------------------------------------------ assertions

    private static void assertRejectedUnchanged(Throwable failure, Map<List<Object>, List<BigDecimal>> before,
                                                List<UUID> products) {
        assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessageContaining("negative balance");
        assertThat(totals(products)).isEqualTo(before);
    }

    private static void assertConsistent(Map<List<Object>, BigDecimal> model, List<UUID> products) {
        Map<List<Object>, List<BigDecimal>> stored = totals(products);
        Map<List<Object>, List<BigDecimal>> fromMovements = HARNESS.movementSums(TestStockRegister.class,
                DIMENSIONS, RESOURCES, "product IN (<products>)".replace("<products>", in(products)), Map.of());
        Map<List<Object>, List<BigDecimal>> expected = new HashMap<>();
        model.forEach((k, v) -> expected.put(k, List.of(v.stripTrailingZeros())));

        // Zero rows may or may not exist in the totals table / movement sums; compare non-zero content.
        assertThat(nonZero(stored)).isEqualTo(nonZero(expected));
        assertThat(nonZero(fromMovements)).isEqualTo(nonZero(expected));
        assertThat(stored.values()).allSatisfy(v -> assertThat(v.getFirst().signum()).isGreaterThanOrEqualTo(0));
    }

    // ------------------------------------------------------------------ model helpers

    private static boolean staysNonNegative(Map<List<Object>, BigDecimal> model, Map<List<Object>, BigDecimal> delta) {
        return delta.entrySet().stream().allMatch(e ->
                model.getOrDefault(e.getKey(), BigDecimal.ZERO).add(e.getValue()).signum() >= 0);
    }

    private static void apply(Map<List<Object>, BigDecimal> model, Map<List<Object>, BigDecimal> delta) {
        delta.forEach((k, v) -> model.merge(k, v, BigDecimal::add));
    }

    private static Map<List<Object>, BigDecimal> negate(Map<List<Object>, BigDecimal> delta) {
        Map<List<Object>, BigDecimal> result = new HashMap<>();
        delta.forEach((k, v) -> result.put(k, v.negate()));
        return result;
    }

    private static Map<List<Object>, List<BigDecimal>> nonZero(Map<List<Object>, List<BigDecimal>> values) {
        Map<List<Object>, List<BigDecimal>> result = new HashMap<>();
        values.forEach((k, v) -> {
            if (v.stream().anyMatch(x -> x.signum() != 0)) result.put(k, v);
        });
        return result;
    }

    private static Map<List<Object>, List<BigDecimal>> totals(List<UUID> products) {
        return HARNESS.totals(TestStockRegister.class, DIMENSIONS, RESOURCES,
                "product IN (" + in(products) + ")", Map.of());
    }

    private static String in(List<UUID> ids) {
        return ids.stream().map(id -> "'" + id + "'").reduce((a, b) -> a + ", " + b).orElseThrow();
    }

    // ------------------------------------------------------------------ documents

    private static TestReceipt receipt(int number, UUID warehouse, List<Line> lines, List<UUID> products) {
        TestReceipt receipt = new TestReceipt();
        prepare(receipt, "R-" + number);
        receipt.setWarehouse(warehouse);
        for (Line line : lines) {
            TestReceiptLine row = new TestReceiptLine();
            row.setProduct(products.get(line.product()));
            row.setQuantity(BigDecimal.valueOf(line.quantity()));
            receipt.getItems().add(row);
        }
        HARNESS.insert(receipt, Map.of("warehouse", warehouse));
        return receipt;
    }

    private static TestStockIssue issue(int number, UUID warehouse, List<Line> lines, List<UUID> products) {
        TestStockIssue issue = new TestStockIssue();
        prepare(issue, "I-" + number);
        issue.setWarehouse(warehouse);
        for (Line line : lines) {
            TestStockIssueLine row = new TestStockIssueLine();
            row.setProduct(products.get(line.product()));
            row.setQuantity(BigDecimal.valueOf(line.quantity()));
            issue.getItems().add(row);
        }
        HARNESS.insert(issue, Map.of("warehouse", warehouse));
        return issue;
    }

    private static void prepare(DocumentObject document, String number) {
        document.setId(UUID.randomUUID());
        document.setNumber(number);
        document.setDate(LocalDateTime.of(2026, 3, 15, 10, 0));
    }

    // ------------------------------------------------------------------ generators

    @Provide
    Arbitrary<List<Step>> scenarios() {
        // A document posts to ONE warehouse (header attribute), lines vary the product.
        Arbitrary<Integer> warehouse = Arbitraries.integers().between(0, 1);
        Arbitrary<List<Line>> lines = warehouse.flatMap(w -> Combinators.combine(
                        Arbitraries.integers().between(0, 2), Arbitraries.integers().between(1, 12))
                .as((p, q) -> new Line(p, w, q)).list().ofMinSize(1).ofMaxSize(3));
        Arbitrary<Step> post = Combinators.combine(Arbitraries.of(true, false), lines)
                .as((issue, l) -> new Step(issue, l, -1));
        Arbitrary<Step> unpost = Arbitraries.integers().between(0, 50).map(i -> new Step(false, List.of(), i));
        return Arbitraries.frequencyOf(
                        net.jqwik.api.Tuple.of(4, post),
                        net.jqwik.api.Tuple.of(1, unpost))
                .list().ofMinSize(1).ofMaxSize(25);
    }
}
