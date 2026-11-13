package su.onno.crm.channels.gmail;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Gmail answers the client must read: whole large emails, rate limits, and the granted scopes. */
class GmailClientParsingTest {
    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");

    private static GmailClient client(ObjectMapper json, Path dir) {
        return new GmailClient(json, dir.resolve("client.json").toString(), dir.resolve("tokens.json").toString(), "http://127.0.0.1/callback");
    }

    @Test void readsAnEmailLargerThanJacksonsDefaultLimitWithoutChangingTheSharedMapper(@TempDir Path dir) throws Exception {
        var shared = new ObjectMapper();
        int sharedLimit = shared.getFactory().streamReadConstraints().getMaxStringLength();
        // About a 20 MB email as Gmail returns it: 28 million base64 characters in one field.
        String body = "{\"id\":\"m1\",\"raw\":\"" + "QUJD".repeat(7_000_000) + "\"}";
        assertThatThrownBy(() -> shared.readTree(body)).isInstanceOf(StreamConstraintsException.class);

        var email = client(shared, dir).read(200, body, null, NOW);

        assertThat(email.path("raw").asText()).hasSize(28_000_000);
        assertThat(shared.getFactory().streamReadConstraints().getMaxStringLength()).isEqualTo(sharedLimit);
    }

    @Test void anUnreadableAnswerKeepsItsStatusAndTheParsersExceptionClass(@TempDir Path dir) {
        var failure = catchThrowableOfType(GmailClient.ApiFailure.class, () -> client(new ObjectMapper(), dir).read(200, "{not json", null, NOW));
        assertThat(failure.status).isEqualTo(200);
        assertThat(failure.describe()).startsWith("200 ").contains("Exception");
        assertThat(failure.rateLimited()).isFalse();
    }

    @Test void rateLimitsKeepRetryAfterFromTheHeaderOrGmailsMessage(@TempDir Path dir) {
        var client = client(new ObjectMapper(), dir);
        var seconds = catchThrowableOfType(GmailClient.ApiFailure.class, () -> client.read(429, "{}", "120", NOW));
        assertThat(seconds.rateLimited()).isTrue();
        assertThat(seconds.retryAfter).isEqualTo(Duration.ofSeconds(120));

        var date = catchThrowableOfType(GmailClient.ApiFailure.class, () -> client.read(429, "", "Tue, 10 Nov 2026 10:05:00 GMT", NOW));
        assertThat(date.retryAfter).isEqualTo(Duration.ofMinutes(5));

        String error = """
            {"error":{"code":403,"message":"User-rate limit exceeded.  Retry after 2026-11-10T10:30:00.000Z",
             "errors":[{"domain":"usageLimits","reason":"rateLimitExceeded"}],"status":"PERMISSION_DENIED"}}""";
        var forbidden = catchThrowableOfType(GmailClient.ApiFailure.class, () -> client.read(403, error, null, NOW));
        assertThat(forbidden.rateLimited()).isTrue();
        assertThat(forbidden.reason).isEqualTo("rateLimitExceeded");
        assertThat(forbidden.retryAfter).isEqualTo(Duration.ofMinutes(30));
        assertThat(forbidden.describe()).isEqualTo("403 rateLimitExceeded");

        var denied = catchThrowableOfType(GmailClient.ApiFailure.class, () -> client.read(403,
                "{\"error\":{\"errors\":[{\"reason\":\"insufficientPermissions\"}]}}", null, NOW));
        assertThat(denied.rateLimited()).isFalse();
        assertThat(denied.retryAfter).isNull();
    }

    @Test void anEmptySuccessfulAnswerIsMissingNotAnError(@TempDir Path dir) {
        assertThat(client(new ObjectMapper(), dir).read(204, "", null, NOW).isMissingNode()).isTrue();
    }

    @Test void newConnectionsAskForModifyButOlderGrantsStillConnect(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("client.json"), "{\"web\":{\"client_id\":\"id\",\"client_secret\":\"secret\"}}");
        var client = client(new ObjectMapper(), dir);
        assertThat(java.net.URLDecoder.decode(client.authorizationUrl("state", "challenge"), java.nio.charset.StandardCharsets.UTF_8))
                .contains("https://www.googleapis.com/auth/gmail.modify");
        String older = GmailClient.READ_SCOPE + " " + GmailClient.SEND_SCOPE;
        assertThat(GmailClient.granted(older, GmailClient.REQUIRED_SCOPES)).isTrue();
        assertThat(GmailClient.granted(GmailClient.READ_SCOPE, GmailClient.REQUIRED_SCOPES)).isFalse();

        assertThat(client.canModify()).isFalse();
        Files.writeString(dir.resolve("tokens.json"), "{\"access_token\":\"x\",\"scope\":\"" + older + "\"}");
        assertThat(client.canModify()).isFalse();
        Files.writeString(dir.resolve("tokens.json"), "{\"access_token\":\"x\",\"scope\":\"" + GmailClient.SCOPES + "\"}");
        assertThat(client.canModify()).isTrue();
    }
}
