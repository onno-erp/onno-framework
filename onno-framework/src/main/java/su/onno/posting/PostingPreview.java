package su.onno.posting;

import java.util.List;
import java.util.Map;

public record PostingPreview(
        String documentType,
        String documentId,
        List<RegisterPreview> registers
) {

    public record RegisterPreview(
            String name,
            String tableName,
            String accumulationType,
            List<Map<String, Object>> movements
    ) {
    }
}
