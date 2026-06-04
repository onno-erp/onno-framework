package com.onec.ui;

import com.onec.events.EntityChangedEvent;
import com.onec.metadata.AttributeDescriptor;
import com.onec.metadata.CatalogDescriptor;
import com.onec.numbering.NumberGenerator;
import com.onec.security.SecretCipher;
import com.onec.security.SecretRedactor;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.Update;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Write-side commands for catalogs — create, update, delete. Extracted from
 * {@code GenericCatalogController} so the REST API and other callers (e.g. the MCP
 * server) share one write path, and read-only mode + {@link UiAccessService} write
 * checks are enforced in a single place against the caller's {@link Principal}.
 */
public class CatalogCommandService {

    private final Jdbi jdbi;
    private final UiProperties properties;
    private final NumberGenerator numberGenerator;
    private final CatalogQueryService query;
    private final UiAccessService access;
    private final ApplicationEventPublisher events;
    private final SecretCipher secretCipher;
    private final WriteValidator writeValidator = new WriteValidator();

    public CatalogCommandService(Jdbi jdbi, UiProperties properties, NumberGenerator numberGenerator,
                                 CatalogQueryService query, UiAccessService access,
                                 ApplicationEventPublisher events, SecretCipher secretCipher) {
        this.jdbi = jdbi;
        this.properties = properties;
        this.numberGenerator = numberGenerator;
        this.query = query;
        this.access = access;
        this.events = events;
        this.secretCipher = secretCipher;
    }

    public Map<String, Object> create(CatalogDescriptor desc, Map<String, Object> body, Principal principal) {
        requireWritable();
        access.requireWrite(principal, desc);
        writeValidator.validate(desc.javaClass(), desc.attributes(), body);
        UUID id = UUID.randomUUID();

        List<String> columns = new ArrayList<>(List.of(
                "_id", "_code", "_description", "_deletion_mark", "_is_folder", "_parent", "_version"));
        List<String> values = new ArrayList<>(List.of(
                ":_id", ":_code", ":_description", ":_deletion_mark", ":_is_folder", ":_parent", ":_version"));

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
                    .bind("_code", resolveCode(desc, body))
                    .bind("_description", body.getOrDefault("description", ""))
                    .bind("_deletion_mark", false)
                    .bind("_is_folder", Boolean.TRUE.equals(body.get("folder")))
                    .bind("_parent", parseUuid(body.get("parent")))
                    .bind("_version", 0);

            for (AttributeDescriptor attr : desc.attributes()) {
                bindAttribute(update, attr, body.get(attr.fieldName()));
            }
            update.execute();
        });

        Map<String, Object> result = query.get(desc, id);
        events.publishEvent(new EntityChangedEvent(EntityChangedEvent.CREATED, EntityChangedEvent.CATALOG,
                desc.logicalName(), id, naturalKey(result)));
        return result;
    }

    public Map<String, Object> update(CatalogDescriptor desc, UUID id, Map<String, Object> body,
                                       Principal principal) {
        requireWritable();
        access.requireWrite(principal, desc);
        writeValidator.validate(desc.javaClass(), desc.attributes(), body);

        List<String> setClauses = new ArrayList<>();
        if (body.containsKey("code")) setClauses.add("_code = :_code");
        if (body.containsKey("description")) setClauses.add("_description = :_description");
        if (body.containsKey("folder")) setClauses.add("_is_folder = :_is_folder");
        if (body.containsKey("parent")) setClauses.add("_parent = :_parent");

        for (AttributeDescriptor attr : desc.attributes()) {
            if (body.containsKey(attr.fieldName()) && !leaveSecretUnchanged(attr, body.get(attr.fieldName()))) {
                setClauses.add(attr.columnName() + " = :" + attr.columnName());
            }
        }

        if (setClauses.isEmpty()) {
            return query.get(desc, id);
        }

        setClauses.add("_version = _version + 1");

        boolean hasExpectedVersion = body.containsKey("version") || body.containsKey("_version");
        String sql = "UPDATE " + desc.tableName() +
                " SET " + String.join(", ", setClauses) +
                " WHERE _id = :_id" + (hasExpectedVersion ? " AND _version = :_expected_version" : "");

        int updated = jdbi.withHandle(h -> {
            var update = h.createUpdate(sql).bind("_id", id);
            if (body.containsKey("code")) update.bind("_code", body.get("code"));
            if (body.containsKey("description")) update.bind("_description", body.get("description"));
            if (body.containsKey("folder")) update.bind("_is_folder", Boolean.TRUE.equals(body.get("folder")));
            if (body.containsKey("parent")) update.bind("_parent", parseUuid(body.get("parent")));
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
                    "Catalog item was changed by another transaction: " + id);
        }

        Map<String, Object> result = query.get(desc, id);
        events.publishEvent(new EntityChangedEvent(EntityChangedEvent.UPDATED, EntityChangedEvent.CATALOG,
                desc.logicalName(), id, naturalKey(result)));
        return result;
    }

    public void delete(CatalogDescriptor desc, UUID id, Principal principal) {
        requireWritable();
        access.requireWrite(principal, desc);
        // Capture the natural key before the soft-delete so listeners can target the resource.
        String code = jdbi.withHandle(h ->
                h.createQuery("SELECT _code FROM " + desc.tableName() + " WHERE _id = :id")
                        .bind("id", id)
                        .mapTo(String.class)
                        .findOne()
                        .orElse(null));
        jdbi.useHandle(h ->
                h.createUpdate("UPDATE " + desc.tableName() + " SET _deletion_mark = true WHERE _id = :id")
                        .bind("id", id)
                        .execute()
        );
        events.publishEvent(new EntityChangedEvent(EntityChangedEvent.DELETED, EntityChangedEvent.CATALOG,
                desc.logicalName(), id, code));
    }

    /** Natural key for a catalog row is its code (the slug used to address the resource). */
    private static String naturalKey(Map<String, Object> row) {
        Object code = row.get("_code");
        return code == null ? null : code.toString();
    }

    private void bindAttribute(Update update, AttributeDescriptor attr, Object value) {
        if (value == null || "".equals(value)) {
            update.bind(attr.columnName(), (UUID) null);
            return;
        }
        if (attr.secret()) {
            // Write-only secret: store the value encrypted. A "set" sentinel (echoed from a
            // GET) carries no real value — on create there's nothing to keep, so store null.
            // On update the caller is filtered out earlier by leaveSecretUnchanged.
            if (SecretRedactor.SET.equals(value)) {
                update.bind(attr.columnName(), (String) null);
            } else {
                update.bind(attr.columnName(), secretCipher.encrypt(value.toString()));
            }
        } else if (attr.isRef() || attr.javaType().isEnum()) {
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

    private UUID parseUuid(Object value) {
        if (value == null || "".equals(value)) return null;
        return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
    }

    private int parseInt(Object value) {
        return value instanceof Number n ? n.intValue() : Integer.parseInt(value.toString());
    }

    private void requireWritable() {
        if (properties.isReadOnly()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "UI is in read-only mode");
        }
    }

    private String resolveCode(CatalogDescriptor desc, Map<String, Object> body) {
        Object explicit = body.get("code");
        if (explicit != null && !explicit.toString().isBlank()) {
            return explicit.toString();
        }
        if (!desc.autoNumber()) {
            return "";
        }
        return numberGenerator.nextCode(desc.tableName(), desc.codePrefix(), desc.codeLength());
    }
}
