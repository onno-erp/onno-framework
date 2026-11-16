package su.onno.crm.web;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import su.onno.crm.domain.Conversation;
import su.onno.crm.repository.ConversationRepository;
import su.onno.metadata.MetadataRegistry;
import su.onno.query.Cursor;
import su.onno.query.KeysetWindow;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.*;

/**
 * Request-local SQL projection for application-authored Java workspace predicates/facets.
 * Source and repository reads are bounded. Only eligible keys/sort values are retained in SQL;
 * no full inbox or activity list is sorted or sliced in application memory.
 */
@Service
public final class CrmFeedQuery {
    private final Jdbi jdbi;
    private final org.springframework.transaction.support.TransactionOperations transactions;
    private final ConversationRepository conversations;
    private final String conversationTable;
    private final String messageTable;

    /** Standalone use with unpooled repositories; Spring supplies the shared transaction manager. */
    public CrmFeedQuery(Jdbi jdbi, ConversationRepository conversations, MetadataRegistry metadata) {
        this(jdbi, conversations, metadata, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    // The repository bean is registered by OnnoJdbcRepositoriesRegistrar at runtime.
    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    public CrmFeedQuery(Jdbi jdbi, ConversationRepository conversations, MetadataRegistry metadata,
                       org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.transactions = transactionManager == null
                ? org.springframework.transaction.support.TransactionOperations.withoutTransaction()
                : new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.jdbi = jdbi;
        this.conversations = conversations;
        conversationTable = metadata.allCatalogs().stream().filter(d -> d.javaClass() == Conversation.class)
                .findFirst().orElseThrow().tableName();
        messageTable = metadata.allCatalogs().stream()
                .filter(d -> d.javaClass() == su.onno.crm.domain.ConversationMessage.class)
                .findFirst().orElseThrow().tableName();
    }

    public record Window(KeysetWindow.Page<UUID> page, Long total) {}
    @FunctionalInterface public interface Sink { void add(UUID id, Object sortValue); }

    /** Bounded source traversal preserves opaque host Java selectors without pretending they are SQL. */
    public void scan(Handle handle, Consumer<List<Conversation>> batch) {
        String cursor = null;
        do {
            var page = KeysetWindow.fetch(handle, conversationTable, "_id", "_deletion_mark = false", q -> {},
                    List.of(new KeysetWindow.Order("_id", false)), cursor, 500, "crm-source:" + conversationTable,
                    (rs, ctx) -> rs.getObject("_id", UUID.class));
            List<Conversation> records = new ArrayList<>();
            conversations.findAllById(page.rows()).forEach(c -> { if (!c.isDeletionMark()) records.add(c); });
            if (!records.isEmpty()) batch.accept(records);
            cursor = page.nextCursor();
        } while (cursor != null);
    }

    public Window inbox(String sort, boolean descending, String cursor, int limit,
            String context, boolean count, BiConsumer<Handle, Sink> populate) {
        return temporary((handle, table) -> {
            populate.accept(handle, (id, value) -> {
                String text = value == null || value instanceof Number || value instanceof LocalDateTime
                        ? null : value.toString().toLowerCase(Locale.ROOT);
                handle.createUpdate("INSERT INTO " + table + " (_id, _sort_text, _sort_number, _sort_time)"
                                + " VALUES (:id, :text, :number, :time)")
                        .bind("id", id).bind("text", text)
                        .bind("number", value instanceof Number ? value : null)
                        .bind("time", value instanceof LocalDateTime ? value : null).execute();
            });
            String column = "unreadCount".equals(sort) ? "_sort_number"
                    : "lastMessageAt".equals(sort) ? "_sort_time" : "_sort_text";
            List<KeysetWindow.Order> order = List.of(new KeysetWindow.Order(column, descending),
                    new KeysetWindow.Order("_id", false));
            BiFunction<String, Integer, KeysetWindow.Page<UUID>> fetch = (c, n) -> KeysetWindow.fetch(
                    handle, table, "*", "1=1", q -> {}, order, c, n, context,
                    (rs, ctx) -> rs.getObject("_id", UUID.class));
            var page = fetch.apply(cursor, limit);
            Long total = count ? handle.createQuery("SELECT COUNT(*) FROM " + table).mapTo(Long.class).one() : null;
            return new Window(page, total);
        });
    }

    /** Messages and comments share one SQL timeline, scoped by eligible conversation keys. */
    public <T> KeysetWindow.Page<T> activity(String cursor, int limit, String context,
            BiConsumer<Handle, Sink> populate, Function<Map<String, Object>, T> decorate) {
        return temporary((handle, table) -> {
            populate.accept(handle, (id, ignored) -> handle.createUpdate("INSERT INTO " + table + " (_id) VALUES (:id)")
                    .bind("id", id).execute());
            boolean any = handle.createQuery("SELECT EXISTS (SELECT 1 FROM " + table + ")").mapTo(Boolean.class).one();
            if (!any) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Contact is outside your inbox workspaces");
            String source = "(SELECT m._id, m.conversation AS _conversation, m.sent_at AS _at, 'message' AS _source"
                    + " FROM " + messageTable + " m JOIN " + table + " a ON a._id = m.conversation"
                    + " WHERE m._deletion_mark = false UNION ALL"
                    + " SELECT n._id, n._entity_id AS _conversation, n._created_at AS _at, 'note' AS _source"
                    + " FROM onno_comments n JOIN " + table + " a ON a._id = n._entity_id"
                    + " WHERE n._deleted = false AND n._entity_type = 'catalogs' AND n._entity_name = 'crm_conversations') timeline";
            List<KeysetWindow.Order> order = List.of(new KeysetWindow.Order("_at", true),
                    new KeysetWindow.Order("_source", true), new KeysetWindow.Order("_id", true));
            BiFunction<String, Integer, KeysetWindow.Page<Map<String, Object>>> fetch = (c, n) -> KeysetWindow.fetch(
                    handle, source, "*", "1=1", q -> {}, order, c, n, context, new org.jdbi.v3.core.mapper.MapMapper());
            var page = fetch.apply(cursor, limit);
            return page.map(decorate);
        });
    }

    private <T> T temporary(BiFunction<Handle, String, T> work) {
        try {
            return transactions.execute(status -> jdbi.withHandle(handle -> {
                String table = "onno_crm_page_" + UUID.randomUUID().toString().replace("-", "");
                handle.execute("CREATE TEMPORARY TABLE " + table
                        + " (_id UUID PRIMARY KEY, _sort_text VARCHAR, _sort_number DECIMAL(38,10), _sort_time TIMESTAMP)");
                try { return work.apply(handle, table); }
                finally { handle.execute("DROP TABLE " + table); }
            }));
        } catch (Cursor.ScopeMismatchException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cursor belongs to another CRM feed or access scope", e);
        }
    }
}
