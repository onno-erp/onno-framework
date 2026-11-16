package su.onno.history;

import java.util.List;
import java.util.Map;

/**
 * A record's stored state at one moment, captured by {@link RecordHistory#capture} before a write
 * and compared with the state after it. Values are canonical text (see {@link HistoryChange});
 * secret attributes hold their plaintext here only for the in-memory comparison and are never
 * written to the history table.
 *
 * @param exists   whether the row existed
 * @param deleted  the deletion mark
 * @param fields   header values by logical field name
 * @param sections tabular-section rows by section name, in line order
 */
public record RecordImage(boolean exists, boolean deleted, Map<String, String> fields,
                          Map<String, List<Map<String, String>>> sections) {

    /** The image of a record that does not exist (before a create). */
    public static final RecordImage ABSENT = new RecordImage(false, false, Map.of(), Map.of());
}
