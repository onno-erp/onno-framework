package su.onno.ui;

import su.onno.events.EntityChangedEvent;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.DocumentDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.metadata.TabularSectionDescriptor;
import su.onno.model.DocumentObject;
import su.onno.model.TabularSectionRow;
import su.onno.numbering.NumberGenerator;
import su.onno.posting.PostingPreview;
import su.onno.posting.PostingService;
import su.onno.security.SecretCipher;
import su.onno.security.SecretRedactor;
import su.onno.types.Ref;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.Update;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Write-side commands for documents — create, update, delete, post, unpost — plus
 * the document reconstruction needed for posting. Extracted from
 * {@code GenericDocumentController} so the same code path is shared by the REST API
 * and other callers (e.g. the MCP server), and access control / read-only checks
 * are enforced in exactly one place.
 *
 * <p>Every mutating method enforces {@code requireWritable()} (global read-only mode)
 * and {@link UiAccessService#requireWrite} against the caller's {@link Principal}, so
 * callers never bypass the role model.
 */
public class DocumentCommandService {

    private final MetadataRegistry registry;
    private final Jdbi jdbi;
    private final UiProperties properties;
    private final NumberGenerator numberGenerator;
    private final PostingService postingService;
    private final DocumentQueryService query;
    private final UiAccessService access;
    private final ApplicationEventPublisher events;
    private final SecretCipher secretCipher;
    private final WriteValidator writeValidator = new WriteValidator();

    public DocumentCommandService(MetadataRegistry registry, Jdbi jdbi, UiProperties properties,
                                  NumberGenerator numberGenerator, PostingService postingService,
                                  DocumentQueryService query, UiAccessService access,
                                  ApplicationEventPublisher events, SecretCipher secretCipher) {
        this.registry = registry;
        this.jdbi = jdbi;
        this.properties = properties;
        this.numberGenerator = numberGenerator;
        this.postingService = postingService;
        this.query = query;
        this.access = access;
        this.events = events;
        this.secretCipher = secretCipher;
    }

    public Map<String, Object> create(DocumentDescriptor desc, Map<String, Object> body, Principal principal) {
        requireWritable();
        access.requireWrite(principal, desc);
        writeValidator.validate(desc.javaClass(), desc.attributes(), body);
        UUID id = UUID.randomUUID();

        List<String> columns = new ArrayList<>(List.of(
                "_id", "_number", "_date", "_posted", "_deletion_mark", "_version"));
        List<String> values = new ArrayList<>(List.of(
                ":_id", ":_number", ":_date", ":_posted", ":_deletion_mark", ":_version"));

        for (AttributeDescriptor attr : desc.attributes()) {
            columns.add(attr.columnName());
            values.add(":" + attr.columnName());
        }

        String sql = "INSERT INTO " + desc.tableName() +
                " (" + String.join(", ", columns) + ")" +
                " VALUES (" + String.join(", ", values) + ")";

        jdbi.useHandle(h -> {
            var update = h.createUpdate(sql)
                    .bind("_id", id)
                    .bind("_number", resolveNumber(desc, body))
                    .bind("_date", body.getOrDefault("date", LocalDateTime.now().toString()))
                    .bind("_posted", false)
                    .bind("_deletion_mark", false)
                    .bind("_version", 0);

            for (AttributeDescriptor attr : desc.attributes()) {
                bindAttribute(update, attr, body.get(attr.fieldName()));
            }
            update.execute();
        });

        insertTabularSections(desc, id, body);

        Map<String, Object> result = query.get(desc, id);
        events.publishEvent(new EntityChangedEvent(EntityChangedEvent.CREATED, EntityChangedEvent.DOCUMENT,
                desc.logicalName(), id, naturalKey(result)));
        return result;
    }

    public Map<String, Object> update(DocumentDescriptor desc, UUID id, Map<String, Object> body,
                                       Principal principal) {
        requireWritable();
        access.requireWrite(principal, desc);
        writeValidator.validate(desc.javaClass(), desc.attributes(), body);

        List<String> setClauses = new ArrayList<>();
        if (body.containsKey("number")) setClauses.add("_number = :_number");
        if (body.containsKey("date")) setClauses.add("_date = :_date");

        for (AttributeDescriptor attr : desc.attributes()) {
            if (body.containsKey(attr.fieldName()) && !leaveSecretUnchanged(attr, body.get(attr.fieldName()))) {
                setClauses.add(attr.columnName() + " = :" + attr.columnName());
            }
        }

        if (!setClauses.isEmpty()) {
            setClauses.add("_version = _version + 1");
            boolean hasExpectedVersion = body.containsKey("version") || body.containsKey("_version");
            String sql = "UPDATE " + desc.tableName() +
                    " SET " + String.join(", ", setClauses) +
                    " WHERE _id = :_id" + (hasExpectedVersion ? " AND _version = :_expected_version" : "");

            int updated = jdbi.withHandle(h -> {
                var update = h.createUpdate(sql).bind("_id", id);
                if (body.containsKey("number")) update.bind("_number", body.get("number"));
                if (body.containsKey("date")) update.bind("_date", body.get("date"));
                if (hasExpectedVersion) {
                    update.bind("_expected_version", parseInt(body.getOrDefault("version", body.get("_version"))));
                }

                for (AttributeDescriptor attr : desc.attributes()) {
                    if (body.containsKey(attr.fieldName()) && !leaveSecretUnchanged(attr, body.get(attr.fieldName()))) {
                        bindAttribute(update, attr, body.get(attr.fieldName()));
                    }
                }
                return update.execute();
            });
            if (updated == 0 && hasExpectedVersion) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Document was changed by another transaction: " + id);
            }
        }

        // Re-insert tabular sections if provided
        for (TabularSectionDescriptor ts : desc.tabularSections()) {
            if (body.containsKey(ts.name())) {
                jdbi.useHandle(h ->
                        h.createUpdate("DELETE FROM " + ts.tableName() + " WHERE _parent_id = :parentId")
                                .bind("parentId", id)
                                .execute()
                );
            }
        }
        insertTabularSections(desc, id, body);

        Map<String, Object> result = query.get(desc, id);
        events.publishEvent(new EntityChangedEvent(EntityChangedEvent.UPDATED, EntityChangedEvent.DOCUMENT,
                desc.logicalName(), id, naturalKey(result)));
        return result;
    }

    public Map<String, Object> post(DocumentDescriptor desc, UUID id, Principal principal) {
        requireWritable();
        access.requireWrite(principal, desc);
        DocumentObject doc = loadDocumentObject(desc, id);
        // Re-posting (1C "Провести" on an already-posted document): reverse the existing
        // register movements before writing fresh ones, otherwise posting twice would
        // double-count. A first-time post sees posted=false and skips this.
        if (doc.isPosted()) {
            postingService.unpost(doc);
        }
        postingService.post(doc);
        Map<String, Object> result = query.get(desc, id);
        events.publishEvent(new EntityChangedEvent(EntityChangedEvent.POSTED, EntityChangedEvent.DOCUMENT,
                desc.logicalName(), id, naturalKey(result)));
        events.publishEvent(new EntityChangedEvent(EntityChangedEvent.CHANGED, EntityChangedEvent.REGISTER,
                "*", id, null));
        return result;
    }

    public PostingPreview postingPreview(DocumentDescriptor desc, UUID id, Principal principal) {
        access.requireRead(principal, desc);
        DocumentObject doc = loadDocumentObject(desc, id);
        return postingService.preview(doc);
    }

    public Map<String, Object> unpost(DocumentDescriptor desc, UUID id, Principal principal) {
        requireWritable();
        access.requireWrite(principal, desc);
        DocumentObject doc = loadDocumentObject(desc, id);
        postingService.unpost(doc);
        Map<String, Object> result = query.get(desc, id);
        events.publishEvent(new EntityChangedEvent(EntityChangedEvent.UNPOSTED, EntityChangedEvent.DOCUMENT,
                desc.logicalName(), id, naturalKey(result)));
        events.publishEvent(new EntityChangedEvent(EntityChangedEvent.CHANGED, EntityChangedEvent.REGISTER,
                "*", id, null));
        return result;
    }

    public void delete(DocumentDescriptor desc, UUID id, Principal principal) {
        requireWritable();
        access.requireWrite(principal, desc);

        // Unpost first if posted
        Map<String, Object> row = jdbi.withHandle(h ->
                h.createQuery("SELECT _posted FROM " + desc.tableName() + " WHERE _id = :id")
                        .bind("id", id)
                        .mapToMap()
                        .findOne()
                        .orElse(null)
        );
        if (row != null && Boolean.TRUE.equals(row.get("_posted"))) {
            DocumentObject doc = loadDocumentObject(desc, id);
            postingService.unpost(doc);
        }

        String number = jdbi.withHandle(h ->
                h.createQuery("SELECT _number FROM " + desc.tableName() + " WHERE _id = :id")
                        .bind("id", id)
                        .mapTo(String.class)
                        .findOne()
                        .orElse(null));
        jdbi.useHandle(h ->
                h.createUpdate("UPDATE " + desc.tableName() + " SET _deletion_mark = true WHERE _id = :id")
                        .bind("id", id)
                        .execute()
        );
        events.publishEvent(new EntityChangedEvent(EntityChangedEvent.DELETED, EntityChangedEvent.DOCUMENT,
                desc.logicalName(), id, number));
    }

    @SuppressWarnings("unchecked")
    private DocumentObject loadDocumentObject(DocumentDescriptor desc, UUID id) {
        Map<String, Object> raw = jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM " + desc.tableName() + " WHERE _id = :id")
                        .bind("id", id)
                        .mapToMap()
                        .findOne()
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND))
        );

        try {
            DocumentObject doc = (DocumentObject) desc.javaClass().getDeclaredConstructor().newInstance();
            doc.setId(id);
            doc.setNumber((String) raw.get("_number"));
            Object dateVal = raw.get("_date");
            if (dateVal instanceof LocalDateTime ldt) {
                doc.setDate(ldt);
            } else if (dateVal instanceof java.sql.Timestamp ts) {
                doc.setDate(ts.toLocalDateTime());
            } else if (dateVal != null) {
                doc.setDate(LocalDateTime.parse(dateVal.toString().replace(' ', 'T')));
            }
            doc.setPosted(Boolean.TRUE.equals(raw.get("_posted")));
            doc.setDeletionMark(Boolean.TRUE.equals(raw.get("_deletion_mark")));
            Object version = raw.get("_version");
            if (version instanceof Number n) {
                doc.setVersion(n.intValue());
            }
            doc.setNew(false);

            for (AttributeDescriptor attr : desc.attributes()) {
                setFieldValue(doc, attr, raw.get(attr.columnName()));
            }

            for (TabularSectionDescriptor ts : desc.tabularSections()) {
                List<Map<String, Object>> rows = jdbi.withHandle(h ->
                        h.createQuery("SELECT * FROM " + ts.tableName() +
                                        " WHERE _parent_id = :parentId ORDER BY _line_number")
                                .bind("parentId", id)
                                .mapToMap()
                                .list()
                );

                List<TabularSectionRow> rowObjects = new ArrayList<>();
                for (Map<String, Object> rowData : rows) {
                    TabularSectionRow rowObj = (TabularSectionRow) ts.rowClass()
                            .getDeclaredConstructor().newInstance();
                    Object rowId = rowData.get("_id");
                    if (rowId instanceof UUID uuid) {
                        rowObj.setId(uuid);
                    } else if (rowId != null) {
                        rowObj.setId(UUID.fromString(rowId.toString()));
                    }
                    Object ln = rowData.get("_line_number");
                    if (ln instanceof Number num) {
                        rowObj.setLineNumber(num.intValue());
                    }

                    for (AttributeDescriptor rowAttr : ts.attributes()) {
                        setFieldValue(rowObj, rowAttr, rowData.get(rowAttr.columnName()));
                    }
                    rowObjects.add(rowObj);
                }

                Field listField = findField(desc.javaClass(), ts.fieldName());
                if (listField != null) {
                    listField.setAccessible(true);
                    listField.set(doc, rowObjects);
                }
            }

            return doc;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to reconstruct document: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private void setFieldValue(Object target, AttributeDescriptor attr, Object value) throws Exception {
        Field field = findField(target.getClass(), attr.fieldName());
        if (field == null || value == null) return;
        field.setAccessible(true);

        Class<?> fieldType = field.getType();

        if (Ref.class.isAssignableFrom(fieldType)) {
            java.lang.reflect.Type genericType = field.getGenericType();
            if (genericType instanceof ParameterizedType pt) {
                Class<?> refTargetClass = (Class<?>) pt.getActualTypeArguments()[0];
                UUID refId = value instanceof UUID u ? u : UUID.fromString(value.toString());
                field.set(target, Ref.of(refTargetClass, refId));
            }
        } else if (fieldType == BigDecimal.class) {
            field.set(target, value instanceof BigDecimal bd ? bd : new BigDecimal(value.toString()));
        } else if (fieldType == String.class) {
            // Secret columns hold ciphertext; decrypt so posting/business code sees plaintext.
            field.set(target, attr.secret() ? secretCipher.decrypt(value.toString()) : value.toString());
        } else if (fieldType == int.class || fieldType == Integer.class) {
            field.set(target, value instanceof Number n ? n.intValue() : Integer.parseInt(value.toString()));
        } else if (fieldType == long.class || fieldType == Long.class) {
            field.set(target, value instanceof Number n ? n.longValue() : Long.parseLong(value.toString()));
        } else if (fieldType == double.class || fieldType == Double.class) {
            field.set(target, value instanceof Number n ? n.doubleValue() : Double.parseDouble(value.toString()));
        } else if (fieldType == boolean.class || fieldType == Boolean.class) {
            field.set(target, Boolean.TRUE.equals(value));
        } else if (fieldType == LocalDate.class) {
            field.set(target, toLocalDate(value));
        } else if (fieldType == LocalDateTime.class) {
            field.set(target, toLocalDateTime(value));
        } else if (fieldType == UUID.class) {
            field.set(target, value instanceof UUID u ? u : UUID.fromString(value.toString()));
        } else if (fieldType.isEnum()) {
            UUID enumId = value instanceof UUID u ? u : UUID.fromString(value.toString());
            var enumDesc = registry.allEnumerations().stream()
                    .filter(e -> e.javaClass().equals(fieldType))
                    .findFirst().orElse(null);
            if (enumDesc != null) {
                enumDesc.values().stream()
                        .filter(v -> v.id().equals(enumId))
                        .findFirst()
                        .ifPresent(v -> {
                            try {
                                field.set(target, Enum.valueOf((Class<Enum>) fieldType, v.name()));
                            } catch (Exception ignored) {}
                        });
            }
        } else {
            field.set(target, value);
        }
    }

    private Field findField(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    /**
     * Coerce a JDBC value into a {@link LocalDate}. The driver may hand back a {@code LocalDate},
     * a {@code java.sql.Date}/{@code Timestamp}, or a string — and a TIMESTAMP renders as
     * {@code "2026-06-04 08:44:44.4"} (space, not {@code T}), so a strict {@code LocalDate.parse}
     * would fail at the space. Take just the date part.
     */
    static LocalDate toLocalDate(Object value) { // package-private for DocumentDateCoercionTest
        if (value instanceof LocalDate ld) return ld;
        if (value instanceof LocalDateTime ldt) return ldt.toLocalDate();
        if (value instanceof java.sql.Date d) return d.toLocalDate();
        if (value instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toLocalDate();
        String s = value.toString();
        return LocalDate.parse(s.length() >= 10 ? s.substring(0, 10) : s);
    }

    /**
     * Coerce a JDBC value into a {@link LocalDateTime}, accepting {@code Timestamp}/{@code Date}
     * instances and both {@code T}- and space-separated strings (H2 returns the latter for
     * TIMESTAMP columns).
     */
    static LocalDateTime toLocalDateTime(Object value) { // package-private for DocumentDateCoercionTest
        if (value instanceof LocalDateTime ldt) return ldt;
        if (value instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        if (value instanceof LocalDate ld) return ld.atStartOfDay();
        if (value instanceof java.sql.Date d) return d.toLocalDate().atStartOfDay();
        return LocalDateTime.parse(value.toString().replace(' ', 'T'));
    }

    /** Natural key for a document row is its number (the slug used to address the resource). */
    private static String naturalKey(Map<String, Object> row) {
        Object number = row.get("_number");
        return number == null ? null : number.toString();
    }

    @SuppressWarnings("unchecked")
    private void insertTabularSections(DocumentDescriptor desc, UUID parentId, Map<String, Object> body) {
        for (TabularSectionDescriptor ts : desc.tabularSections()) {
            Object rawRows = body.get(ts.name());
            if (!(rawRows instanceof List<?> rows)) continue;

            int lineNumber = 1;
            for (Object rawRow : rows) {
                if (!(rawRow instanceof Map<?, ?> row)) continue;
                Map<String, Object> typedRow = (Map<String, Object>) row;

                List<String> columns = new ArrayList<>(List.of("_id", "_parent_id", "_line_number"));
                List<String> values = new ArrayList<>(List.of(":_id", ":_parent_id", ":_line_number"));

                for (AttributeDescriptor attr : ts.attributes()) {
                    columns.add(attr.columnName());
                    values.add(":" + attr.columnName());
                }

                String sql = "INSERT INTO " + ts.tableName() +
                        " (" + String.join(", ", columns) + ")" +
                        " VALUES (" + String.join(", ", values) + ")";

                int ln = lineNumber;
                jdbi.useHandle(h -> {
                    var update = h.createUpdate(sql)
                            .bind("_id", UUID.randomUUID())
                            .bind("_parent_id", parentId)
                            .bind("_line_number", ln);

                    for (AttributeDescriptor attr : ts.attributes()) {
                        bindAttribute(update, attr, typedRow.get(attr.fieldName()));
                    }
                    update.execute();
                });
                lineNumber++;
            }
        }
    }

    /**
     * Properly converts and binds an attribute value, handling Ref UUIDs and enums.
     */
    private void bindAttribute(Update update, AttributeDescriptor attr, Object value) {
        if (value == null || "".equals(value)) {
            update.bind(attr.columnName(), (UUID) null);
            return;
        }
        if (attr.secret()) {
            // Write-only secret: store encrypted. A "set" sentinel echoed from a GET carries no
            // real value (on create there's nothing to keep, so store null; on update such
            // columns are dropped earlier by leaveSecretUnchanged).
            if (SecretRedactor.SET.equals(value)) {
                update.bind(attr.columnName(), (String) null);
            } else {
                update.bind(attr.columnName(), secretCipher.encrypt(value.toString()));
            }
        } else if (attr.isRef() || attr.javaType().isEnum()) {
            // Ref and enum columns are UUID in the DB
            UUID uuid = value instanceof UUID u ? u : UUID.fromString(value.toString());
            update.bind(attr.columnName(), uuid);
        } else if (attr.javaType() == BigDecimal.class) {
            update.bind(attr.columnName(), value instanceof BigDecimal bd ? bd : new BigDecimal(value.toString()));
        } else {
            update.bind(attr.columnName(), value);
        }
    }

    /**
     * A secret attribute whose incoming value is the read-side "set" sentinel means "leave it
     * as it is" — the client round-tripped a GET it never saw the real value of. Such columns
     * are dropped from the UPDATE so the stored ciphertext is preserved.
     */
    private boolean leaveSecretUnchanged(AttributeDescriptor attr, Object value) {
        return attr.secret() && SecretRedactor.SET.equals(value);
    }

    private int parseInt(Object value) {
        return value instanceof Number n ? n.intValue() : Integer.parseInt(value.toString());
    }

    private void requireWritable() {
        if (properties.isReadOnly()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "UI is in read-only mode");
        }
    }

    private String resolveNumber(DocumentDescriptor desc, Map<String, Object> body) {
        Object explicit = body.get("number");
        if (explicit != null && !explicit.toString().isBlank()) {
            return explicit.toString();
        }
        if (!desc.autoNumber()) {
            return "";
        }
        return numberGenerator.nextNumber(desc.tableName(), desc.numberPrefix(), desc.numberLength());
    }
}
