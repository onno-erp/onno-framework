package com.onec.kafka;

import java.time.OffsetDateTime;

/**
 * A decoded inbound CloudEvent delivered to {@link EventHandler}s. The {@link #data()}
 * payload is the raw JSON body; handlers deserialize it into their own type.
 */
public record InboundEvent(
        String id,
        String source,
        String type,
        String subject,
        OffsetDateTime time,
        String data
) {
}
