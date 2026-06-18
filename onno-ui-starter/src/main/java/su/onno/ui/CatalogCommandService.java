package su.onno.ui;

import su.onno.events.EntityChangedEvent;
import su.onno.metadata.AttributeDescriptor;
import su.onno.metadata.CatalogDescriptor;
import su.onno.metadata.MetadataRegistry;
import su.onno.model.CatalogObject;
import su.onno.numbering.NumberGenerator;
import su.onno.security.SecretCipher;
import su.onno.security.SecretRedactor;
import su.onno.validation.AttributeValidator;
import su.onno.validation.ValidationErrors;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.Update;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final AttributeValidator attributeValidator = new AttributeValidator();
    private final WriteLifecycle lifecycle;

    public CatalogCommandService(MetadataRegistry registry, Jdbi jdbi, UiProperties properties,
                                 NumberGenerator numberGenerator, CatalogQueryService query,
                                 UiAccessService access, ApplicationEventPublisher events,
                                 SecretCipher secretCipher) {
        this.jdbi = jdbi;
        this.properties = properties;
        this.numberGenerator = numberGenerator;
        this.query = query;
        this.access = access;
        this.events = events;
        this.secretCipher = secretCipher;
        this.lifecycle = new WriteLifecycle(registry, secretCipher);
    }

    public Map<String, Object> create(CatalogDescriptor desc, Map<String, Object> requestBody, Principal principal) {
        requireWritable();
        access.requireWrite(principal, desc);
        Map<String, Object> body = new LinkedHashMap<>(requestBody); // working copy; hooks may derive fields we merge in

        UUID id = UUID.randomUUID();
        String code = resolveCode(desc, body);
        body.put("code", code); // resolved once; the INSERT below echoes it rather than regenerating

        // Run the same write lifecycle the repository path runs (onFilling + beforeWrite + rules),
        // then fold any field a hook derived back into the body so the INSERT captures it. (#158)
        ValidationErrors errors = new ValidationErrors();
        attributeValidator.validate(body, desc.attributes(), errors);
        CatalogObject entity = instantiate(desc.javaClass());
        if (entity != null) {
            entity.setId(id);
            entity.setCode(code);
            entity.setDescription(asString(body.getOrDefault("description", "")));
            entity.setFolder(Boolean.TRUE.equals(body.get("folder")));
            entity.setParent(parseUuid(body.get("parent")));
            lifecycle.applyBody(entity, desc.attributes(), body, errors);
            Map<String, Object> before = lifecycle.snapshot(entity, desc.attributes());
            if (bake(entity, true, errors)) {
                lifecycle.writeBackDerived(entity, desc.attributes(), before, body);
                body.put("code", entity.getCode());
                body.put("description", entity.getDescription());
                body.put("folder", entity.isFolder());
                body.put("parent", entity.getParent());
            }
        }
        errors.throwIfAny();

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

    public Map<String, Object> update(CatalogDescriptor desc, UUID id, Map<String, Object> requestBody,
                                       Principal principal) {
        requireWritable();
        access.requireWrite(principal, desc);
        Map<String, Object> body = new LinkedHashMap<>(requestBody); // working copy; hooks may derive fields we merge in

        // Reconstruct the stored item, overlay the submitted changes, and run the write lifecycle
        // (beforeWrite + rules) on the merged state so derived fields recompute the way they do on
        // the repository path; only fields a hook actually changed are folded back in, leaving the
        // partial-update semantics intact. (#158)
        ValidationErrors errors = new ValidationErrors();
        attributeValidator.validate(body, desc.attributes(), errors);
        CatalogObject entity = loadCatalogObject(desc, id);
        if (entity != null) {
            if (body.containsKey("code")) entity.setCode(asString(body.get("code")));
            if (body.containsKey("description")) entity.setDescription(asString(body.get("description")));
            if (body.containsKey("folder")) entity.setFolder(Boolean.TRUE.equals(body.get("folder")));
            if (body.containsKey("parent")) entity.setParent(parseUuid(body.get("parent")));
            lifecycle.applyBody(entity, desc.attributes(), body, errors);
            Map<String, Object> before = lifecycle.snapshot(entity, desc.attributes());
            String codeBefore = entity.getCode();
            String descriptionBefore = entity.getDescription();
            boolean folderBefore = entity.isFolder();
            UUID parentBefore = entity.getParent();
            if (bake(entity, false, errors)) {
                lifecycle.writeBackDerived(entity, desc.attributes(), before, body);
                if (!Objects.equals(entity.getCode(), codeBefore)) body.put("code", entity.getCode());
                if (!Objects.equals(entity.getDescription(), descriptionBefore)) body.put("description", entity.getDescription());
                if (entity.isFolder() != folderBefore) body.put("folder", entity.isFolder());
                if (!Objects.equals(entity.getParent(), parentBefore)) body.put("parent", entity.getParent());
            }
        }
        errors.throwIfAny();

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

    /**
     * Run {@code onFilling}/{@code beforeWrite} and collect business rules. A hook or rule that
     * throws on otherwise-valid input is a genuine failure and propagates; when other validation
     * errors are already pending it is suppressed, since those explain the broken state and would
     * otherwise be masked by a {@code 500}.
     */
    private boolean bake(Object entity, boolean isNew, ValidationErrors errors) {
        try {
            lifecycle.runHooks(entity, isNew);
            lifecycle.collectRules(entity, errors);
            return true;
        } catch (RuntimeException failed) {
            if (errors.isEmpty()) {
                throw failed;
            }
            return false;
        }
    }

    /** Reconstruct the stored catalog item so the write lifecycle runs on its real state. */
    private CatalogObject loadCatalogObject(CatalogDescriptor desc, UUID id) {
        Map<String, Object> raw = jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM " + desc.tableName() + " WHERE _id = :id")
                        .bind("id", id)
                        .mapToMap()
                        .findOne()
                        .orElse(null));
        if (raw == null) {
            return null;
        }
        CatalogObject entity = instantiate(desc.javaClass());
        if (entity == null) {
            return null;
        }
        entity.setId(id);
        entity.setCode(asString(raw.get("_code")));
        entity.setDescription(asString(raw.get("_description")));
        entity.setDeletionMark(Boolean.TRUE.equals(raw.get("_deletion_mark")));
        entity.setFolder(Boolean.TRUE.equals(raw.get("_is_folder")));
        entity.setParent(parseUuid(raw.get("_parent")));
        if (raw.get("_version") instanceof Number n) {
            entity.setVersion(n.intValue());
        }
        entity.setNew(false);
        for (AttributeDescriptor attr : desc.attributes()) {
            lifecycle.setFromStored(entity, attr, raw.get(attr.columnName()));
        }
        return entity;
    }

    private static CatalogObject instantiate(Class<?> javaClass) {
        try {
            var ctor = javaClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            return (CatalogObject) ctor.newInstance();
        } catch (ReflectiveOperationException cannotBuild) {
            return null; // no usable no-arg constructor: fall back to the body-only write path
        }
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
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
