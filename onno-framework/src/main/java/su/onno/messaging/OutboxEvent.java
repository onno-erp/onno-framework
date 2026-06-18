package su.onno.messaging;

import java.time.LocalDateTime;
import java.util.UUID;

public record OutboxEvent(
        UUID id,
        String aggregateType,
        String aggregateId,
        String eventType,
        String payload,
        LocalDateTime createdAt,
        LocalDateTime publishedAt,
        String status
) {
}
