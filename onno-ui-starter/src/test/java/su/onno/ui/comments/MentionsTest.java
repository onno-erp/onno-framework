package su.onno.ui.comments;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@link Mentions} token syntax — {@code @[Display](kind/name/id)} for mentions and
 * {@code #[Display](kind/name/id)} for references — round-trips: what
 * {@link Mentions#token} writes, {@link Mentions#parse}/{@link Mentions#occurrences} read back, and
 * {@link Mentions#degrade} rewrites only the tokens a predicate selects, leaving surrounding prose and
 * the other tokens untouched.
 */
class MentionsTest {

    private final UUID acme = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID globex = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final UUID invoice = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    void tokenAndParseRoundTrip() {
        MentionRef ref = new MentionRef("catalogs", "customers", acme);
        String token = Mentions.token("Acme Corp", ref);

        assertThat(token).isEqualTo("@[Acme Corp](catalogs/customers/" + acme + ")");
        assertThat(Mentions.parse(token)).containsExactly(ref);

        List<Mentions.Occurrence> occ = Mentions.occurrences(token);
        assertThat(occ).singleElement().satisfies(o -> {
            assertThat(o.ref()).isEqualTo(ref);
            assertThat(o.marker()).isEqualTo('@');
            assertThat(o.label()).isEqualTo("Acme Corp");
            assertThat(o.token()).isEqualTo(token);
        });
    }

    @Test
    void referenceTokenAndMarkerSpecificParseRoundTrip() {
        MentionRef ref = new MentionRef("documents", "sales_orders", invoice);
        String token = Mentions.token('#', "SO-1", ref);

        assertThat(token).isEqualTo("#[SO-1](documents/sales_orders/" + invoice + ")");
        assertThat(Mentions.parse(token)).containsExactly(ref);
        assertThat(Mentions.parse(token, '#')).containsExactly(ref);
        assertThat(Mentions.parse(token, '@')).isEmpty();
        assertThat(Mentions.occurrences(token)).singleElement()
                .satisfies(o -> assertThat(o.marker()).isEqualTo('#'));
    }

    @Test
    void parsesMixedProseAndIsDistinctInFirstSeenOrder() {
        String body = "cc " + Mentions.token("Acme", new MentionRef("catalogs", "customers", acme))
                + " and " + Mentions.token("SO-1", new MentionRef("documents", "sales_orders", invoice))
                + " and again " + Mentions.token("Acme renamed", new MentionRef("catalogs", "customers", acme));

        // Three occurrences (the duplicate included), but two distinct refs in first-seen order.
        assertThat(Mentions.occurrences(body)).hasSize(3);
        assertThat(Mentions.parse(body)).containsExactly(
                new MentionRef("catalogs", "customers", acme),
                new MentionRef("documents", "sales_orders", invoice));
    }

    @Test
    void degradeStripsOnlySelectedTokensToTheirLabel() {
        MentionRef visible = new MentionRef("catalogs", "customers", acme);
        MentionRef hidden = new MentionRef("catalogs", "customers", globex);
        String body = "Ping " + Mentions.token("Acme", visible) + " not " + Mentions.token("Globex", hidden) + "!";

        String degraded = Mentions.degrade(body, ref -> ref.equals(hidden));

        // The hidden mention collapses to its plain label; the visible token and the prose survive.
        assertThat(degraded).isEqualTo("Ping " + Mentions.token("Acme", visible) + " not Globex!");
        assertThat(Mentions.parse(degraded)).containsExactly(visible);
    }

    @Test
    void degradeAllLeavesNoTokens() {
        String body = "a " + Mentions.token("Acme", new MentionRef("catalogs", "customers", acme)) + " b";
        assertThat(Mentions.parse(Mentions.degrade(body, ref -> true))).isEmpty();
    }

    @Test
    void ignoresMalformedAndPlainText() {
        assertThat(Mentions.parse(null)).isEmpty();
        assertThat(Mentions.parse("no mentions here")).isEmpty();
        // Missing kind, non-uuid id, and an unclosed token are all ignored.
        assertThat(Mentions.parse("@[X](widgets/foo/" + acme + ")")).isEmpty();
        assertThat(Mentions.parse("@[X](catalogs/customers/not-a-uuid)")).isEmpty();
        assertThat(Mentions.parse("@[X](catalogs/customers/" + acme)).isEmpty();
    }

    @Test
    void sanitizeLabelKeepsSyntaxIntact() {
        // A label containing ] would otherwise close the token early; it's stripped, and newlines
        // collapse to spaces — so the token always parses back to one mention.
        String token = Mentions.token("Weird]\nName", new MentionRef("catalogs", "customers", acme));
        assertThat(Mentions.occurrences(token)).singleElement()
                .satisfies(o -> assertThat(o.label()).isEqualTo("Weird Name"));
        assertThat(Mentions.sanitizeLabel("   ")).isEqualTo("?");
    }

    @Test
    void routeNameSnakeCasesLikeTheClient() {
        assertThat(Mentions.routeName("Sales Orders")).isEqualTo("sales_orders");
        assertThat(Mentions.routeName("Counterparties")).isEqualTo("counterparties");
        assertThat(Mentions.routeName("PurchaseOrder")).isEqualTo("purchase_order");
    }
}
