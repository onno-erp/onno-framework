package su.onno.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class EventPublisherTest {

    private final List<EventPublisher> publishers = new ArrayList<>();

    @AfterEach
    void shutDownPublishers() {
        publishers.forEach(EventPublisher::shutdown);
    }

    @Test
    void failedEventSendRemovesSubscriberWithoutCompletingErroredStreamAgain() {
        FailingSseEmitter emitter = new FailingSseEmitter();
        EventPublisher publisher = publisherWith(emitter);
        publisher.subscribe(Set.of("USER"), "alex");
        emitter.failWrites();

        assertThatCode(() -> publisher.publishNotification("alex", Map.of("id", "notification-1")))
                .doesNotThrowAnyException();
        publisher.publishNotification("alex", Map.of("id", "notification-2"));

        assertThat(emitter.sendAttempts).isEqualTo(2);
        assertThat(emitter.completeWithErrorCalls).isZero();
    }

    @Test
    void failedKeepaliveRemovesSubscriberWithoutCompletingErroredStreamAgain() {
        FailingSseEmitter emitter = new FailingSseEmitter();
        EventPublisher publisher = publisherWith(emitter);
        publisher.subscribe(Set.of("USER"), "alex");
        emitter.failWrites();

        assertThatCode(publisher::ping).doesNotThrowAnyException();
        publisher.ping();

        assertThat(emitter.sendAttempts).isEqualTo(2);
        assertThat(emitter.completeWithErrorCalls).isZero();
    }

    private EventPublisher publisherWith(FailingSseEmitter emitter) {
        EventPublisher publisher =
                new EventPublisher(new AccessService(null), false, () -> emitter);
        publishers.add(publisher);
        return publisher;
    }

    private static final class FailingSseEmitter extends SseEmitter {
        private boolean failWrites;
        private int sendAttempts;
        private int completeWithErrorCalls;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            sendAttempts++;
            if (failWrites) {
                throw new IOException("client disconnected");
            }
        }

        @Override
        public void completeWithError(Throwable ex) {
            completeWithErrorCalls++;
            throw new AssertionError("failed writes must not complete the emitter again", ex);
        }

        void failWrites() {
            failWrites = true;
        }
    }
}
