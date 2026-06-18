package su.onno.kafka;

import java.time.OffsetDateTime;

public record CloudEvent(
        String specversion,
        String id,
        String source,
        String type,
        String subject,
        OffsetDateTime time,
        String datacontenttype,
        String data
) {
}
