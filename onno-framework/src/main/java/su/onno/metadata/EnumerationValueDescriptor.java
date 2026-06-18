package su.onno.metadata;

import java.util.UUID;

public record EnumerationValueDescriptor(
        String name,
        UUID id,
        int order
) {
}
