package su.onno.crm.channels.telegram;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Network work done for one update before the polling transaction and workspace lock are taken.
 *
 * @param businessConnection {@code getBusinessConnection} metadata fetched for a business message
 *                           whose connection was not yet known, or null
 * @param files              the imported files for the message, or null when no import was attempted
 */
record TelegramPreparedUpdate(JsonNode businessConnection, TelegramAttachments.Imported files) {
    static final TelegramPreparedUpdate NONE = new TelegramPreparedUpdate(null, null);

    /** Files to put on the message: a placeholder when the message has a file that was not imported. */
    TelegramAttachments.Imported filesFor(JsonNode message) {
        if (files != null) return files;
        return TelegramAttachments.hasFile(message)
                ? new TelegramAttachments.Imported(null, TelegramAttachments.NOT_IMPORTED)
                : TelegramAttachments.Imported.NONE;
    }
}
