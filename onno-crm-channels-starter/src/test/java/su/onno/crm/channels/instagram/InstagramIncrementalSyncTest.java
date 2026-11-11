package su.onno.crm.channels.instagram;

import static org.assertj.core.api.Assertions.assertThat;
import static su.onno.crm.channels.instagram.InstagramFixture.ACCOUNT;
import static su.onno.crm.channels.instagram.InstagramFixture.T0;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import su.onno.crm.domain.MessageDirection;

/** Issue #538: new-messages-only cutoff and bounded, incremental, head-first polling. */
class InstagramIncrementalSyncTest {

    private static Instant at(long secondsFromCutoff) { return T0.plusSeconds(secondsFromCutoff); }

    @Test
    void thousandsOfOldThreadsImportNothingAndCostOneListCall() {
        var f = new InstagramFixture();
        for (int i = 0; i < 3000; i++) {
            f.graph.inbound("p" + i, T0.minus(Duration.ofHours(3000 - i)), "old " + i);
            f.graph.outbound("p" + i, T0.minus(Duration.ofHours(3000 - i)).plusSeconds(60), "old reply " + i);
        }
        f.account(true);
        f.restart();

        f.poll();

        assertThat(f.messageStore).isEmpty();
        assertThat(f.graph.messageCalls).isZero();
        assertThat(f.graph.conversationCalls).isEqualTo(1);
        assertThat(f.failure()).isBlank();
        assertThat(f.checkpoint()).isNotNull();
    }

    @Test
    void newMessagesInOldAndNewThreadsArriveExactlyOnceWithoutTheirHistory() {
        var f = new InstagramFixture();
        for (int i = 0; i < 2000; i++) f.graph.inbound("p" + i, T0.minus(Duration.ofMinutes(5 + i)), "history " + i);
        f.graph.inbound("p1500", T0.minus(Duration.ofDays(400)), "ancient history");
        f.account(true);
        f.restart();
        f.poll();
        assertThat(f.messageStore).isEmpty();

        f.graph.inbound("p1500", f.clock.instant().plusSeconds(3), "hello again");
        f.graph.inbound("newcomer", f.clock.instant().plusSeconds(5), "first contact");
        f.graph.outbound("newcomer", f.clock.instant().plusSeconds(9), "studio answer from the app");
        for (int i = 0; i < 12; i++) f.poll();

        // Head-first: the most recently updated conversation is imported first; each in its own order.
        assertThat(f.bodies()).containsExactlyInAnyOrder("hello again", "first contact", "studio answer from the app");
        assertThat(f.bodies().indexOf("first contact")).isLessThan(f.bodies().indexOf("studio answer from the app"));
        assertThat(f.messageStore.stream().filter(m -> m.getBody().startsWith("studio")).findFirst().orElseThrow().getDirection())
                .isEqualTo(MessageDirection.OUTBOUND);
        assertThat(f.conversationStore).hasSize(2);
        // Only the changed threads were downloaded, a bounded number of times while their timestamps settled.
        assertThat(f.graph.messageCalls).isBetween(2, 2 * 6);
        assertThat(f.graph.conversationCalls).isEqualTo(13);
    }

    @Test
    void cutoffAndCheckpointTiesAreInclusive() {
        var f = new InstagramFixture();
        f.graph.inbound("tie", at(-1), "one second before the cutoff");
        f.graph.inbound("tie", at(0), "exactly at the cutoff");
        f.account(true);
        f.clock.set(at(30));
        f.restart();
        f.bridge.tick();
        assertThat(f.bodies()).containsExactly("exactly at the cutoff");

        // Same second as the thread's last processed updated_time: the timestamp does not move.
        f.graph.inbound("tie", at(0), "same second, later");
        f.poll();
        assertThat(f.bodies()).containsExactly("exactly at the cutoff", "same second, later");

        // A thread updated exactly at checkpoint − overlap is still inside the window.
        f.graph.inbound("later", at(600), "moves the checkpoint");
        f.clock.set(at(700));
        f.bridge.tick();
        Instant checkpoint = Instant.parse(f.checkpoint());
        assertThat(checkpoint).isEqualTo(at(600));
        f.graph.inbound("edge", checkpoint.minus(InstagramBridge.OVERLAP), "at the overlap edge");
        f.poll();
        assertThat(f.bodies()).contains("at the overlap edge").hasSize(4);

        for (int i = 0; i < 8; i++) f.poll();
        assertThat(f.bodies()).hasSize(4);
    }

    @Test
    void paginationIsBoundedAndABacklogNeverDelaysAFreshMessage() {
        var f = new InstagramFixture();
        f.properties.getInstagram().setConversationPagesPerPoll(2);
        f.properties.getInstagram().setThreadsPerPoll(5);
        f.account(true);
        f.restart();
        f.bridge.tick(); // checkpoint at the (empty) head
        // Paused for an hour: 120 conversations got a message after the cutoff, plus older history.
        for (int i = 0; i < 120; i++) {
            f.graph.inbound("b" + i, at(-3600 - i), "pre-cutoff " + i);
            f.graph.inbound("b" + i, at(60 + i * 20L), "backlog " + i);
        }
        f.clock.set(at(3 * 3600));

        f.graph.conversationCalls = 0;
        f.graph.messageCalls = 0;
        f.bridge.tick();
        assertThat(f.graph.conversationCalls).isLessThanOrEqualTo(2);
        assertThat(f.graph.messageCalls).isLessThanOrEqualTo(5);
        assertThat(f.messageStore).hasSize(5);
        assertThat(f.passHead()).isNotNull();

        f.graph.inbound("fresh", f.clock.instant().plusSeconds(5), "fresh DM");
        f.graph.conversationCalls = 0;
        f.graph.messageCalls = 0;
        f.poll();
        assertThat(f.bodies()).contains("fresh DM");
        assertThat(f.graph.conversationCalls).isLessThanOrEqualTo(2);
        assertThat(f.graph.messageCalls).isLessThanOrEqualTo(5);

        for (int i = 0; i < 60 && f.passHead() != null; i++) f.poll();
        assertThat(f.passHead()).isNull();
        assertThat(f.bodies()).hasSize(121).doesNotHaveDuplicates().noneMatch(b -> b.startsWith("pre-cutoff"));
        for (int i = 0; i < 10; i++) f.poll();
        assertThat(f.bodies()).hasSize(121);
    }

    @Test
    void failedInsertAdvancesNoWatermarkAndTheRetryImportsTheRestExactlyOnce() {
        var f = new InstagramFixture();
        f.account(true);
        f.restart();
        f.bridge.tick();
        String before = f.checkpoint();
        f.graph.inbound("anna", f.clock.instant().plusSeconds(1), "m1");
        f.graph.inbound("anna", f.clock.instant().plusSeconds(2), "m2");
        f.graph.inbound("anna", f.clock.instant().plusSeconds(3), "m3");
        f.failOnce = m -> "m2".equals(m.getBody());

        f.poll();

        assertThat(f.failure()).isNotBlank();
        assertThat(f.bodies()).containsExactly("m1");
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM onno_crm_ig_thread", Integer.class)).isZero();
        assertThat(f.checkpoint()).isEqualTo(before);
        assertThat(f.passHead()).isNull();

        f.poll(); // still backing off
        assertThat(f.bodies()).containsExactly("m1");
        f.clock.advance(Duration.ofSeconds(60));
        f.poll();
        assertThat(f.failure()).isBlank();
        assertThat(f.bodies()).containsExactly("m1", "m2", "m3");
        assertThat(Instant.parse(f.checkpoint())).isAfter(Instant.parse(before));
    }

    @Test
    void rateLimitsBackOffWithoutCallingTheProvider() {
        var f = new InstagramFixture();
        f.account(true);
        f.restart();
        f.graph.next = new InstagramClient.ApiFailure(400, 4, 0, 0);
        f.poll();
        assertThat(f.failure()).contains("rate limit");
        assertThat(f.graph.conversationCalls).isEqualTo(1);

        f.clock.advance(Duration.ofSeconds(280));
        f.bridge.tick();
        assertThat(f.graph.conversationCalls).isEqualTo(1);

        f.clock.advance(Duration.ofSeconds(30));
        f.graph.next = new InstagramClient.ApiFailure(429, 0, 0, 1200);
        f.bridge.tick();
        assertThat(f.graph.conversationCalls).isEqualTo(2);
        f.clock.advance(Duration.ofSeconds(1100));
        f.bridge.tick();
        assertThat(f.graph.conversationCalls).isEqualTo(2);

        f.graph.inbound("waiting", f.clock.instant(), "sent during the backoff");
        f.clock.advance(Duration.ofSeconds(120));
        f.bridge.tick();
        assertThat(f.failure()).isBlank();
        assertThat(f.bodies()).containsExactly("sent during the backoff");
    }

    @Test
    void providerFailuresKeepOnlyNumericDiagnostics() {
        var client = new InstagramClient("unused", new ObjectMapper());
        var failure = client.apiFailure(400, null, "{\"123\":[{\"type\":\"messaging\",\"estimated_time_to_regain_access\":7}]}",
                "{\"error\":{\"code\":613,\"error_subcode\":2,\"message\":\"secret text access_token=abc\"}}");
        assertThat(failure.rateLimited()).isTrue();
        assertThat(failure.retrySeconds).isEqualTo(420);
        assertThat(failure.getMessage()).doesNotContain("secret").doesNotContain("access_token");
        var auth = client.apiFailure(401, "30", null, "not json");
        assertThat(auth.rateLimited()).isFalse();
        assertThat(auth.accessDenied()).isTrue();
        assertThat(auth.retrySeconds).isEqualTo(30);
        assertThat(client.apiFailure(429, null, null, "").rateLimited()).isTrue();
        for (int code : new int[] {4, 17, 32, 613}) assertThat(client.apiFailure(400, null, null, "{\"error\":{\"code\":" + code + "}}").rateLimited()).isTrue();
    }

    @Test
    void restartKeepsTheCutoffAndCheckpointAndImportsNothingTwice() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        var f = new InstagramFixture(url);
        f.account(true);
        f.restart();
        f.graph.inbound("anna", at(100), "before restart");
        f.clock.set(at(200));
        f.bridge.tick();
        String checkpoint = f.checkpoint();
        assertThat(f.bodies()).containsExactly("before restart");

        // A later deployment configures a later cutoff: the persisted one still wins.
        f.properties.getInstagram().setActivationCutoff(at(86_400));
        f.restart();
        assertThat(f.cutoff()).isEqualTo(T0.toString());
        assertThat(f.checkpoint()).isEqualTo(checkpoint);

        f.graph.inbound("anna", at(300), "after restart");
        f.clock.set(at(400));
        f.bridge.tick();
        for (int i = 0; i < 6; i++) f.poll();
        assertThat(f.bodies()).containsExactly("before restart", "after restart");
    }

    @Test
    void pauseStopsPollingAndResumeKeepsTheCutoff() {
        var f = new InstagramFixture();
        f.account(true);
        f.restart();
        f.bridge.tick();
        f.bridge.command("pause", null);
        f.graph.inbound("anna", f.clock.instant().plusSeconds(10), "while paused");
        int calls = f.graph.conversationCalls;
        for (int i = 0; i < 4; i++) f.poll();
        assertThat(f.graph.conversationCalls).isEqualTo(calls);
        assertThat(f.messageStore).isEmpty();

        f.clock.advance(Duration.ofHours(5));
        f.bridge.command("resume", null);
        assertThat(f.cutoff()).isEqualTo(T0.toString());
        f.poll();
        assertThat(f.bodies()).containsExactly("while paused");
    }

    @Test
    void operatorPersistsAnExplicitCutoffWhileSyncIsStillPaused() {
        // Procedure A: the property seeds the cutoff at startup, before anyone resumes.
        var f = new InstagramFixture();
        f.properties.getInstagram().setActivationCutoff(at(600));
        f.graph.inbound("anna", at(300), "before the operator's cutoff");
        f.account(false);
        f.restart();
        assertThat(f.cutoff()).isEqualTo(at(600).toString());
        f.clock.set(at(7200));
        f.bridge.tick();
        assertThat(f.graph.conversationCalls).isZero();
        f.bridge.command("resume", null);
        assertThat(f.cutoff()).isEqualTo(at(600).toString());
        f.graph.inbound("anna", at(700), "after the operator's cutoff");
        f.poll();
        assertThat(f.bodies()).containsExactly("after the operator's cutoff");

        // Procedure B: plain SQL while paused; without a property the row is respected as written.
        var g = new InstagramFixture();
        g.properties.getInstagram().setActivationCutoff(null);
        g.account(false);
        g.restart();
        assertThat(g.jdbc.queryForObject("SELECT COUNT(*) FROM onno_crm_ig_sync", Integer.class)).isZero();
        g.jdbc.update("INSERT INTO onno_crm_ig_sync (account_id, activation_cutoff) VALUES (?, ?)", ACCOUNT, "2026-11-12T09:10:00Z");
        g.graph.inbound("bob", at(599), "just before");
        g.graph.inbound("bob", at(600), "at the SQL cutoff");
        g.clock.set(at(3600));
        g.bridge.command("resume", null);
        g.poll();
        assertThat(g.bodies()).containsExactly("at the SQL cutoff");
        assertThat(g.cutoff()).isEqualTo("2026-11-12T09:10:00Z");
    }

    @Test
    void withoutAConfiguredCutoffEnablingSeedsNowAndNeverBackfills() {
        var f = new InstagramFixture();
        f.properties.getInstagram().setActivationCutoff(null);
        f.graph.inbound("anna", at(-60), "an hour of history");
        f.clock.set(at(0));
        f.restart(); // no account yet: the first poll connects it
        f.bridge.tick();
        assertThat(f.cutoff()).isEqualTo(T0.toString());
        assertThat(f.messageStore).isEmpty();
        f.graph.inbound("anna", at(30), "new");
        f.poll();
        f.poll();
        assertThat(f.bodies()).containsExactly("new");
    }

    @Test
    void theCutoffPropertyBindsAsAUtcInstant() {
        var source = new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(java.util.Map.of(
                "onno.crm.channels.instagram.activation-cutoff", "2026-11-12T09:00:00Z",
                "onno.crm.channels.instagram.threads-per-poll", "7"));
        var bound = new org.springframework.boot.context.properties.bind.Binder(source)
                .bind("onno.crm.channels", su.onno.crm.channels.CrmChannelsProperties.class).get();
        assertThat(bound.getInstagram().getActivationCutoff()).isEqualTo(T0);
        assertThat(bound.getInstagram().getThreadsPerPoll()).isEqualTo(7);
    }

    @Test
    void anInvalidPersistedCutoffStopsSynchronization() {
        var f = new InstagramFixture();
        f.account(true);
        f.restart();
        f.jdbc.update("UPDATE onno_crm_ig_sync SET activation_cutoff='12/11/2026'");
        f.graph.inbound("anna", at(30), "must not import");
        f.poll();
        assertThat(f.failure()).contains("cutoff is invalid");
        assertThat(f.graph.conversationCalls).isZero();
        assertThat(f.messageStore).isEmpty();
    }
}
