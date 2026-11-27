package su.onno.crm.channels.instagram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static su.onno.crm.channels.instagram.InstagramFixture.ACCOUNT;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import su.onno.crm.domain.ContactIdentity;

/** Profile pictures of the people who message the account: fetched slowly, cached, re-checked weekly. */
class InstagramAvatarTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};

    /** A fixture with {@code n} people who wrote after the cutoff, each with a known identity and a picture. */
    private static InstagramFixture people(int n) {
        var f = new InstagramFixture();
        for (int i = 0; i < n; i++) f.graph.inbound("p" + i, f.clock.instant().plusSeconds(i + 1), "hello " + i);
        when(f.identities.findActiveById(any())).thenAnswer(call -> {
            var identity = new ContactIdentity();
            identity.setId(call.getArgument(0));
            return Optional.of(identity);
        });
        when(f.client.profilePicture(anyString())).thenAnswer(call -> "https://scontent.cdninstagram.com/" + call.getArgument(0) + ".jpg");
        when(f.client.picture(anyString())).thenReturn(new InstagramClient.Image(JPEG, "image/jpeg"));
        f.account(true);
        f.restart();
        return f;
    }

    private static UUID identity(InstagramFixture f, String peer) {
        return UUID.nameUUIDFromBytes(("INSTAGRAM\n" + f.inbox + "\n" + peer).getBytes(StandardCharsets.UTF_8));
    }

    private static String stored(InstagramFixture f, String peer) {
        var rows = f.jdbc.queryForList("SELECT content FROM onno_crm_ig_avatar WHERE account_id=? AND peer_id=?", String.class, ACCOUNT, peer);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Test
    void picturesAreCachedAFewPerPollAndPointAtTheCrmsOwnUrl() {
        var f = people(5);
        f.poll(); // imports the five chats, then looks up the first pictures

        verify(f.client, times(InstagramBridge.AVATARS_PER_POLL)).profilePicture(anyString());
        f.poll();
        verify(f.client, times(5)).profilePicture(anyString());

        for (int i = 0; i < 5; i++) {
            UUID id = identity(f, "p" + i);
            assertThat(Base64.getDecoder().decode(stored(f, "p" + i))).isEqualTo(JPEG);
            verify(f.contacts).setIdentityAvatar(id, "/api/crm/instagram/avatars/" + id);
        }
        // The most recent writers come first.
        var order = inOrder(f.client);
        order.verify(f.client).profilePicture("p4");
        order.verify(f.client).profilePicture("p3");
    }

    @Test
    void eachPictureIsCheckedAgainOnlyAfterAWeek() {
        var f = people(1);
        f.poll();
        for (int i = 0; i < 20; i++) f.poll();
        verify(f.client, times(1)).profilePicture("p0");

        f.clock.advance(InstagramBridge.AVATAR_REFRESH.plus(Duration.ofMinutes(1)));
        f.poll();
        verify(f.client, times(2)).profilePicture("p0");
    }

    @Test
    void aFailedDownloadKeepsThePictureAndAMissingOneClearsIt() {
        var f = people(1);
        f.poll();
        UUID id = identity(f, "p0");
        assertThat(stored(f, "p0")).isNotEmpty();

        when(f.client.profilePicture("p0")).thenThrow(new IllegalStateException("network"));
        f.clock.advance(InstagramBridge.AVATAR_REFRESH.plus(Duration.ofMinutes(1)));
        f.poll();
        assertThat(stored(f, "p0")).isNotEmpty();
        verify(f.contacts, never()).setIdentityAvatar(eq(id), isNull());

        doReturn(null).when(f.client).profilePicture("p0");
        f.clock.advance(InstagramBridge.AVATAR_REFRESH.plus(Duration.ofMinutes(1)));
        f.poll();
        assertThat(stored(f, "p0")).isEmpty();
        verify(f.contacts).setIdentityAvatar(id, null);
    }

    @Test
    void aRateLimitStopsTheLookupsAndBacksOffTheWholeAdapter() {
        var f = people(3);
        when(f.client.profilePicture(anyString())).thenThrow(new InstagramClient.ApiFailure(400, 4, 0, 0));
        f.poll();
        verify(f.client, times(1)).profilePicture(anyString());
        assertThat(f.failure()).contains("rate limit");
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM onno_crm_ig_avatar", Integer.class)).isZero();
    }

    @Test
    void anotherPersonsGraphErrorIsRecordedNotRetriedEveryPoll() {
        var f = people(1);
        when(f.client.profilePicture("p0")).thenThrow(new InstagramClient.ApiFailure(400, 100, 33, 0));
        f.poll();
        for (int i = 0; i < 10; i++) f.poll();
        verify(f.client, times(1)).profilePicture("p0");
        assertThat(f.failure()).isBlank();
    }

    @Test
    void onlyHttpsInstagramCdnUrlsAreDownloaded() {
        assertThat(InstagramClient.cdn(URI.create("https://scontent-ams2-1.cdninstagram.com/v/t51/1.jpg?oh=x"))).isTrue();
        assertThat(InstagramClient.cdn(URI.create("https://scontent.xx.fbcdn.net/v/1.jpg"))).isTrue();
        assertThat(InstagramClient.cdn(URI.create("http://scontent.cdninstagram.com/1.jpg"))).isFalse();
        assertThat(InstagramClient.cdn(URI.create("https://cdninstagram.com.evil.example/1.jpg"))).isFalse();
        assertThat(InstagramClient.cdn(URI.create("https://evilcdninstagram.com/1.jpg"))).isFalse();
        assertThat(InstagramClient.cdn(URI.create("https://user@scontent.cdninstagram.com/1.jpg"))).isFalse();
        assertThat(InstagramClient.cdn(URI.create("https://scontent.cdninstagram.com:8443/1.jpg"))).isFalse();
    }
}
